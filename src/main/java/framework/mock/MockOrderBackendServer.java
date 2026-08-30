package framework.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lightweight, in-process, stateful mock backend for the Order & Warehouse
 * Management System, satisfying Assessment Section 4 - Option B (mock the backend).
 *
 * Deliberately implemented on the JDK's built-in HttpServer rather than
 * WireMock: all business/state logic (inventory arithmetic, idempotency
 * replay, time-based async status progression) lives in plain, reviewable
 * Java with zero extra runtime dependency, rather than being expressed via
 * a mocking library's stub-matching/extension DSL. Trade-off discussed in
 * README - this does not give us WireMock's request-verification/recording
 * features, which we don't need here since correctness is asserted against
 * the framework's own validators, not against the mock's call log.
 *
 * Async simulation: after inventory is reserved, order status progresses
 * PROCESSING -> CONFIRMED -> SHIPPED -> DELIVERED purely based on elapsed
 * wall-clock time (see STAGE_DELAY). This lets Scenario 5's polling helper
 * be tested against genuinely time-delayed state changes instead of an
 * immediately-consistent stub, without making the test suite slow.
 */
public class MockOrderBackendServer {

    private static final Logger log = LoggerFactory.getLogger(MockOrderBackendServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ORDER_ID_PATH = Pattern.compile("^/orders/([^/]+)$");
    private static final Pattern ORDER_STATUS_PATH = Pattern.compile("^/orders/([^/]+)/status$");

    /** Elapsed time after inventory reservation before each async stage kicks in. Kept short for test speed. */
    private static final Duration PROCESSING_TO_CONFIRMED = Duration.ofSeconds(4);
    private static final Duration CONFIRMED_TO_SHIPPED = Duration.ofSeconds(8);
    private static final Duration SHIPPED_TO_DELIVERED = Duration.ofSeconds(12);

    private final InMemoryBackendStore store = InMemoryBackendStore.getInstance();
    private HttpServer server;
    private final int port;

    public MockOrderBackendServer(int port) {
        this.port = port;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/orders", this::routeOrders);
            server.createContext("/payments", this::handleProcessPayment);
            server.createContext("/inventory/reserve", this::handleReserveInventory);
            server.setExecutor(null);
            server.start();
            log.info("Mock order backend started on port {}", port);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start mock backend on port " + port, e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("Mock order backend stopped");
        }
    }

    public int getPort() {
        return port;
    }

    // ----------------------------------------------------------------
    // Routing
    // ----------------------------------------------------------------

    private void routeOrders(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        Matcher statusMatcher = ORDER_STATUS_PATH.matcher(path);
        Matcher orderMatcher = ORDER_ID_PATH.matcher(path);

        if (path.equals("/orders") && method.equals("POST")) {
            handleCreateOrder(exchange);
        } else if (statusMatcher.matches() && method.equals("GET")) {
            handleGetOrderStatus(exchange, statusMatcher.group(1));
        } else if (orderMatcher.matches() && method.equals("GET")) {
            handleGetOrder(exchange, orderMatcher.group(1));
        } else {
            sendJson(exchange, 404, errorBody("NOT_FOUND", "No such route: " + method + " " + path));
        }
    }

    // ----------------------------------------------------------------
    // API 1: Create Order (with idempotency-key support - Scenario 4)
    // ----------------------------------------------------------------

    private void handleCreateOrder(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String idempotencyKey = firstHeader(exchange, "Idempotency-Key");

        if (!body.hasNonNull("customerId") || !body.hasNonNull("productId") || !body.hasNonNull("quantity")) {
            sendJson(exchange, 400, errorBody("INVALID_REQUEST", "customerId, productId and quantity are required"));
            return;
        }
        int quantity = body.get("quantity").asInt();
        if (quantity <= 0) {
            sendJson(exchange, 400, errorBody("INVALID_QUANTITY", "quantity must be greater than zero"));
            return;
        }

        // Whether this call actually created the order or is replaying an
        // existing one (for the same idempotency key) is only known once
        // getOrCreateOrderForIdempotencyKey returns - the supplier only
        // runs if no order exists yet for this key.
        boolean[] wasCreated = {false};
        OrderRecord record = store.getOrCreateOrderForIdempotencyKey(idempotencyKey, () -> {
            wasCreated[0] = true;
            OrderRecord newRecord = new OrderRecord();
            newRecord.setOrderId(store.nextOrderId());
            newRecord.setCustomerId(body.get("customerId").asText());
            newRecord.setProductId(body.get("productId").asText());
            newRecord.setQuantity(quantity);
            newRecord.setStatus("CREATED");
            newRecord.setCreatedAt(Instant.now());
            newRecord.setIdempotencyKey(idempotencyKey);
            return newRecord;
        });

        if (wasCreated[0]) {
            log.info("Order created: {}", record.getOrderId());
            sendJson(exchange, 201, toOrderJson(record));
        } else {
            log.info("Idempotent replay for key={}, returning existing orderId={}", idempotencyKey, record.getOrderId());
            sendJson(exchange, 200, toOrderJson(record));
        }
    }

    // ----------------------------------------------------------------
    // API 2: Process Payment (Scenario 2 - payment failure)
    // ----------------------------------------------------------------

    private void handleProcessPayment(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            sendJson(exchange, 405, errorBody("METHOD_NOT_ALLOWED", "Only POST is supported"));
            return;
        }
        JsonNode body = readJson(exchange);
        if (!body.hasNonNull("orderId")) {
            sendJson(exchange, 400, errorBody("INVALID_REQUEST", "orderId is required"));
            return;
        }
        String orderId = body.get("orderId").asText();
        OrderRecord record = store.getOrder(orderId);
        if (record == null) {
            sendJson(exchange, 404, errorBody("ORDER_NOT_FOUND", "No order with id " + orderId));
            return;
        }

        double amount = body.hasNonNull("amount") ? body.get("amount").asDouble() : -1;
        String paymentMethod = body.hasNonNull("paymentMethod") ? body.get("paymentMethod").asText() : "";

        // Deterministic failure triggers so tests are explicit, not random:
        // paymentMethod = "DECLINED_CARD" or a non-positive amount simulates a gateway decline.
        boolean shouldFail = "DECLINED_CARD".equalsIgnoreCase(paymentMethod) || amount <= 0;

        ObjectNode response = MAPPER.createObjectNode();
        response.put("orderId", orderId);

        if (shouldFail) {
            record.setStatus("FAILED");
            record.setPaymentAt(Instant.now());
            response.put("paymentId", (String) null);
            response.put("status", "FAILED");
            response.put("reason", "PAYMENT_DECLINED");
            log.info("Payment failed for order {}", orderId);
            sendJson(exchange, 402, response.toString());
            return;
        }

        String paymentId = store.nextPaymentId();
        record.setPaymentId(paymentId);
        record.setStatus("PAYMENT_SUCCESS");
        record.setPaymentAt(Instant.now());

        response.put("paymentId", paymentId);
        response.put("status", "SUCCESS");
        log.info("Payment succeeded for order {}", orderId);
        sendJson(exchange, 200, response.toString());
    }

    // ----------------------------------------------------------------
    // API 5: Reserve Inventory (Scenario 3 - insufficient inventory)
    // ----------------------------------------------------------------

    private void handleReserveInventory(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            sendJson(exchange, 405, errorBody("METHOD_NOT_ALLOWED", "Only POST is supported"));
            return;
        }
        JsonNode body = readJson(exchange);
        String orderId = body.hasNonNull("orderId") ? body.get("orderId").asText() : null;
        String productId = body.hasNonNull("productId") ? body.get("productId").asText() : null;
        int quantity = body.hasNonNull("quantity") ? body.get("quantity").asInt() : 0;

        if (orderId == null || productId == null || quantity <= 0) {
            sendJson(exchange, 400, errorBody("INVALID_REQUEST", "orderId, productId and a positive quantity are required"));
            return;
        }

        OrderRecord record = store.getOrder(orderId);
        if (record == null) {
            sendJson(exchange, 404, errorBody("ORDER_NOT_FOUND", "No order with id " + orderId));
            return;
        }

        boolean reserved = store.tryReserveInventory(productId, quantity);
        if (!reserved) {
            log.info("Insufficient inventory for product {} (requested {}, available {})",
                    productId, quantity, store.getAvailableInventory(productId));
            record.setStatus("FAILED");
            sendJson(exchange, 409, errorBody("INSUFFICIENT_INVENTORY",
                    "Only " + store.getAvailableInventory(productId) + " units available for " + productId));
            return;
        }

        record.setStatus("INVENTORY_RESERVED");
        record.setInventoryReservedAt(Instant.now());

        ObjectNode response = MAPPER.createObjectNode();
        response.put("orderId", orderId);
        response.put("productId", productId);
        response.put("quantity", quantity);
        response.put("status", "RESERVED");
        log.info("Inventory reserved for order {}", orderId);
        sendJson(exchange, 200, response.toString());
    }

    // ----------------------------------------------------------------
    // API 3: Get Order Details
    // ----------------------------------------------------------------

    private void handleGetOrder(HttpExchange exchange, String orderId) throws IOException {
        OrderRecord record = store.getOrder(orderId);
        if (record == null) {
            sendJson(exchange, 404, errorBody("ORDER_NOT_FOUND", "No order with id " + orderId));
            return;
        }
        advanceAsyncStatus(record);
        sendJson(exchange, 200, toOrderJson(record));
    }

    // ----------------------------------------------------------------
    // API 4: Get Order Status (drives Scenario 5 - async polling)
    // ----------------------------------------------------------------

    private void handleGetOrderStatus(HttpExchange exchange, String orderId) throws IOException {
        OrderRecord record = store.getOrder(orderId);
        if (record == null) {
            sendJson(exchange, 404, errorBody("ORDER_NOT_FOUND", "No order with id " + orderId));
            return;
        }
        advanceAsyncStatus(record);

        ObjectNode response = MAPPER.createObjectNode();
        response.put("orderId", orderId);
        response.put("status", record.getStatus());
        sendJson(exchange, 200, response.toString());
    }

    /**
     * Purely time-driven state progression: once inventory is reserved, the
     * order "processes" in the background and moves through CONFIRMED ->
     * SHIPPED -> DELIVERED as wall-clock time passes, independent of how
     * often (or rarely) the client polls. This is what makes Scenario 5's
     * polling helper meaningful to test, rather than the mock simply
     * returning the final state on the first call.
     */
    private void advanceAsyncStatus(OrderRecord record) {
        if (record.getInventoryReservedAt() == null) {
            return; // hasn't reached the async stage yet
        }
        Duration elapsed = Duration.between(record.getInventoryReservedAt(), Instant.now());
        String current = record.getStatus();

        if (isTerminal(current)) {
            return;
        }

        String next;
        if (elapsed.compareTo(SHIPPED_TO_DELIVERED) >= 0) {
            next = "DELIVERED";
        } else if (elapsed.compareTo(CONFIRMED_TO_SHIPPED) >= 0) {
            next = "SHIPPED";
        } else if (elapsed.compareTo(PROCESSING_TO_CONFIRMED) >= 0) {
            next = "CONFIRMED";
        } else {
            next = "PROCESSING";
        }
        record.setStatus(next);
    }

    private boolean isTerminal(String status) {
        return List.of("CANCELLED", "FAILED", "DELIVERED").contains(status);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String toOrderJson(OrderRecord record) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("orderId", record.getOrderId());
        node.put("customerId", record.getCustomerId());
        node.put("productId", record.getProductId());
        node.put("quantity", record.getQuantity());
        node.put("status", record.getStatus());
        return node.toString();
    }

    private String errorBody(String code, String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("errorCode", code);
        node.put("message", message);
        return node.toString();
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(bytes);
    }

    private String firstHeader(HttpExchange exchange, String name) {
        Map<String, List<String>> headers = exchange.getRequestHeaders();
        List<String> values = headers.get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
