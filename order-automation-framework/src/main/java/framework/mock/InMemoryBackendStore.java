package framework.mock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Singleton, thread-safe store simulating the persistence layer of the
 * Order & Warehouse system for mock/WireMock-extension purposes.
 *
 * Deliberately simple (no real DB) - the framework's own OrderDbHelper /
 * OrderEventHelper validate against the *real* MySQL/MongoDB test
 * containers, not against this store. This store exists only so the mock
 * API layer can behave statefully (inventory decrements, idempotency
 * replay, order lookups) instead of returning static JSON.
 */
public class InMemoryBackendStore {

    private static final InMemoryBackendStore INSTANCE = new InMemoryBackendStore();

    private final Map<String, OrderRecord> orders = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeyToOrderId = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inventory = new ConcurrentHashMap<>();
    private final AtomicInteger orderSequence = new AtomicInteger(10000);
    private final AtomicInteger paymentSequence = new AtomicInteger(50000);

    private InMemoryBackendStore() {
        seedInventory();
    }

    public static InMemoryBackendStore getInstance() {
        return INSTANCE;
    }

    private void seedInventory() {
        inventory.put("PROD001", new AtomicInteger(100));
        inventory.put("PROD002", new AtomicInteger(50));
        // Deliberately low stock product to drive the insufficient-inventory scenario (Scenario 3)
        inventory.put("PROD-LOW-STOCK", new AtomicInteger(5));
    }

    public String nextOrderId() {
        return "ORD" + orderSequence.incrementAndGet();
    }

    public String nextPaymentId() {
        return "PAY" + paymentSequence.incrementAndGet();
    }

    public void saveOrder(OrderRecord record) {
        orders.put(record.getOrderId(), record);
    }

    public OrderRecord getOrder(String orderId) {
        return orders.get(orderId);
    }

    public String resolveIdempotentOrderId(String idempotencyKey) {
        return idempotencyKeyToOrderId.get(idempotencyKey);
    }

    /**
     * Atomically returns the orderId already associated with this
     * idempotency key, or creates one via `orderFactory` and associates it
     * if none exists yet.
     *
     * This exists because the obvious "check resolveIdempotentOrderId(),
     * then call this to link it" sequence is a check-then-act race: two
     * concurrent requests with the same key can both observe "not present"
     * before either writes, and both create a distinct order. ConcurrentHashMap
     * guarantees computeIfAbsent evaluates the mapping function atomically
     * with respect to other calls for the same key, so only one caller's
     * orderFactory result ever wins for a given idempotencyKey - this is
     * what OrderIdempotencyTest's concurrent test is exercising.
     */
    public OrderRecord getOrCreateOrderForIdempotencyKey(String idempotencyKey, java.util.function.Supplier<OrderRecord> orderFactory) {
        if (idempotencyKey == null) {
            OrderRecord record = orderFactory.get();
            saveOrder(record);
            return record;
        }
        String orderId = idempotencyKeyToOrderId.computeIfAbsent(idempotencyKey, key -> {
            OrderRecord record = orderFactory.get();
            saveOrder(record);
            return record.getOrderId();
        });
        return getOrder(orderId);
    }

    public int getAvailableInventory(String productId) {
        return inventory.computeIfAbsent(productId, p -> new AtomicInteger(0)).get();
    }

    /**
     * Atomically reserves inventory. Returns true if reservation succeeded,
     * false if insufficient stock (and leaves inventory untouched, so it
     * never goes negative).
     */
    public boolean tryReserveInventory(String productId, int quantity) {
        AtomicInteger stock = inventory.computeIfAbsent(productId, p -> new AtomicInteger(0));
        return stock.getAndUpdate(current -> current >= quantity ? current - quantity : current) >= quantity;
    }

    /** Test-only: resets all state between test classes / suites if needed. */
    public void reset() {
        orders.clear();
        idempotencyKeyToOrderId.clear();
        inventory.clear();
        seedInventory();
    }
}
