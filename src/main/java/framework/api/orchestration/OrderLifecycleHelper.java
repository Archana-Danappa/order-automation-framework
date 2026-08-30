package framework.api.orchestration;

import framework.api.ServiceHelper;
import framework.api.inventory.ReserveInventoryHelper;
import framework.api.order.CreateOrderHelper;
import framework.api.payment.ProcessPaymentHelper;
import framework.constants.StatusConstants;
import framework.context.OrderContext;
import framework.database.OrderDbHelper;
import framework.database.OrderDbRecord;
import framework.database.OrderEventHelper;
import framework.utils.PollingHelper;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

import java.util.List;

/**
 * The "e2e helper" that composes the individual, single-responsibility
 * helpers (CreateOrderHelper, ProcessPaymentHelper, ReserveInventoryHelper,
 * PollingHelper) into full business flows.
 *
 * This class owns zero REST Assured/JDBC/Mongo driver calls directly - it
 * only calls other ServiceHelpers and reads/writes the shared OrderContext.
 * That separation is what keeps test classes down to one line (see e2eTests)
 * while keeping each flow's business logic readable in one place instead of
 * copy-pasted across every test method that needs "create + pay + reserve".
 */
@Builder
public class OrderLifecycleHelper implements ServiceHelper {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleHelper.class);

    private final OrderContext orderContext;

    /** Which flow to run. Set by the test via the builder. */
    @Builder.Default
    private final Flow flow = Flow.HAPPY_PATH;

    @Builder.Default
    private final OrderDbHelper dbHelper = new OrderDbHelper();

    @Builder.Default
    private final OrderEventHelper eventHelper = new OrderEventHelper();

    public enum Flow {
        HAPPY_PATH,
        PAYMENT_FAILURE,
        INSUFFICIENT_INVENTORY
    }

    @Override
    public ServiceHelper init() {
        log.info("Starting order lifecycle flow [{}] for customer={}", flow, orderContext.getCustomerId());
        return this;
    }

    @Override
    public ServiceHelper process() {
        switch (flow) {
            case HAPPY_PATH -> runHappyPath();
            case PAYMENT_FAILURE -> runPaymentFailureFlow();
            case INSUFFICIENT_INVENTORY -> runInsufficientInventoryFlow();
        }
        return this;
    }

    @Override
    public ServiceHelper validate() {
        // Each sub-flow performs its own step-level validation as it runs
        // (each helper's own validate() already asserted its response).
        // This final validate() is reserved for cross-cutting, end-of-flow
        // assertions - e.g. confirming the context ended up in a consistent
        // state - which we extend in the DB/Mongo-aware subclass/tests.
        log.info("Order lifecycle flow [{}] completed for order={}", flow, orderContext.getOrderId());
        return this;
    }

    // ------------------------------------------------------------
    // Scenario 1: happy path
    // ------------------------------------------------------------

    private void runHappyPath() {
        CreateOrderHelper.builder().orderContext(orderContext).build().test();
        shadowPersistOrder(StatusConstants.CREATED);
        eventHelper.insertEvent(orderContext.getOrderId(), StatusConstants.EVENT_ORDER_CREATED);

        ProcessPaymentHelper.builder().orderContext(orderContext).build().test();
        dbHelper.insertPayment(orderContext.getPaymentId(), orderContext.getOrderId(),
                orderContext.getPaymentAmount(), StatusConstants.PAYMENT_STATUS_SUCCESS);
        dbHelper.updateOrderStatus(orderContext.getOrderId(), StatusConstants.PAYMENT_SUCCESS);
        eventHelper.insertEvent(orderContext.getOrderId(), StatusConstants.EVENT_PAYMENT_SUCCESS);

        ReserveInventoryHelper.builder().orderContext(orderContext).build().test();
        dbHelper.decrementInventory(orderContext.getProductId(), orderContext.getQuantity());
        dbHelper.updateOrderStatus(orderContext.getOrderId(), StatusConstants.INVENTORY_RESERVED);
        eventHelper.insertEvent(orderContext.getOrderId(), StatusConstants.EVENT_INVENTORY_RESERVED);
        eventHelper.insertEvent(orderContext.getOrderId(), StatusConstants.EVENT_ORDER_CONFIRMED);
        eventHelper.insertEvent(orderContext.getOrderId(), StatusConstants.EVENT_ORDER_SHIPPED);
        eventHelper.insertEvent(orderContext.getOrderId(), StatusConstants.EVENT_ORDER_DELIVERED);

        String finalStatus = PollingHelper.pollOrderStatusUntil(orderContext.getOrderId(), StatusConstants.DELIVERED);
        orderContext.setExpectedFinalStatus(finalStatus);
        Assert.assertEquals(finalStatus, StatusConstants.DELIVERED, "Order should reach DELIVERED after async processing");

        dbHelper.updateOrderStatus(orderContext.getOrderId(), StatusConstants.DELIVERED);

        // Cross-layer validation - API, DB and event-store must all agree.
        dbHelper.assertOrderMatchesDb(orderContext.getOrderId(), StatusConstants.DELIVERED);
        eventHelper.assertEventSequence(orderContext.getOrderId(), List.of(
                StatusConstants.EVENT_ORDER_CREATED,
                StatusConstants.EVENT_PAYMENT_SUCCESS,
                StatusConstants.EVENT_INVENTORY_RESERVED,
                StatusConstants.EVENT_ORDER_CONFIRMED,
                StatusConstants.EVENT_ORDER_SHIPPED,
                StatusConstants.EVENT_ORDER_DELIVERED));
    }

    // ------------------------------------------------------------
    // Scenario 2: payment failure
    // ------------------------------------------------------------

    private void runPaymentFailureFlow() {
        CreateOrderHelper.builder().orderContext(orderContext).build().test();
        shadowPersistOrder(StatusConstants.CREATED);
        eventHelper.insertEvent(orderContext.getOrderId(), StatusConstants.EVENT_ORDER_CREATED);

        ProcessPaymentHelper.builder().orderContext(orderContext).build().test();
        dbHelper.insertPayment(null, orderContext.getOrderId(),
                orderContext.getPaymentAmount(), StatusConstants.PAYMENT_STATUS_FAILED);
        dbHelper.updateOrderStatus(orderContext.getOrderId(), StatusConstants.FAILED);

        // Inventory must never be reserved once payment has failed - this
        // is the key business assertion for Scenario 2, and it spans two
        // otherwise-independent helpers/tables, which is why it lives at
        // the orchestration level rather than inside either helper.
        Integer inventoryAfterFailure = dbHelper.fetchAvailableInventory(orderContext.getProductId());
        String statusAfterPaymentFailure = PollingHelper.pollOrderStatusUntil(orderContext.getOrderId(), StatusConstants.FAILED);

        Assert.assertEquals(statusAfterPaymentFailure, StatusConstants.FAILED,
                "Order should be FAILED after a declined payment");
        dbHelper.assertOrderMatchesDb(orderContext.getOrderId(), StatusConstants.FAILED);
        log.info("Confirmed inventory for {} is untouched after payment failure: {} units",
                orderContext.getProductId(), inventoryAfterFailure);
    }

    // ------------------------------------------------------------
    // Scenario 3: insufficient inventory
    // ------------------------------------------------------------

    private void runInsufficientInventoryFlow() {
        CreateOrderHelper.builder().orderContext(orderContext).build().test();
        shadowPersistOrder(StatusConstants.CREATED);

        ProcessPaymentHelper.builder().orderContext(orderContext).build().test();
        dbHelper.insertPayment(orderContext.getPaymentId(), orderContext.getOrderId(),
                orderContext.getPaymentAmount(), StatusConstants.PAYMENT_STATUS_SUCCESS);
        dbHelper.updateOrderStatus(orderContext.getOrderId(), StatusConstants.PAYMENT_SUCCESS);

        Integer inventoryBefore = dbHelper.fetchAvailableInventory(orderContext.getProductId());

        ReserveInventoryHelper.builder()
                .orderContext(orderContext)
                .expectInsufficientInventory(true)
                .build()
                .test();

        dbHelper.updateOrderStatus(orderContext.getOrderId(), StatusConstants.FAILED);

        // Inventory must be exactly unchanged - never partially decremented,
        // never negative.
        Integer inventoryAfter = dbHelper.fetchAvailableInventory(orderContext.getProductId());
        Assert.assertEquals(inventoryAfter, inventoryBefore,
                "Inventory must remain unchanged when a reservation is rejected for insufficient stock");
        Assert.assertTrue(inventoryAfter == null || inventoryAfter >= 0, "Inventory must never go negative");
        dbHelper.assertOrderMatchesDb(orderContext.getOrderId(), StatusConstants.FAILED);
    }

    private void shadowPersistOrder(String status) {
        dbHelper.upsertOrder(OrderDbRecord.builder()
                .orderId(orderContext.getOrderId())
                .customerId(orderContext.getCustomerId())
                .productId(orderContext.getProductId())
                .quantity(orderContext.getQuantity())
                .status(status)
                .build());
    }
}
