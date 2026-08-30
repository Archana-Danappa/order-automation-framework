package e2eTests;

import baseTests.BaseTest;
import framework.api.orchestration.OrderLifecycleHelper;
import framework.context.OrderContext;
import org.testng.annotations.Test;

/**
 * Test classes stay thin by design (see reference pattern in README /
 * Architecture doc): each method states *what* business scenario is being
 * verified and delegates entirely to OrderLifecycleHelper. No endpoint,
 * payload, or assertion detail lives here - that's what makes these tests
 * survive an API contract change without being touched themselves.
 */
public class OrderLifecycleE2ETest extends BaseTest {

    @Test(groups = {"smoke", "e2e"}, description = "Scenario 1: full order lifecycle reaches DELIVERED, validated across API/DB/events")
    public void verifySuccessfulOrderDeliveryFlow() {
        OrderContext context = OrderContext.builder()
                .customerId("CUST001")
                .productId("PROD001")
                .quantity(2)
                .build();

        OrderLifecycleHelper.builder()
                .orderContext(context)
                .flow(OrderLifecycleHelper.Flow.HAPPY_PATH)
                .build()
                .test();
    }

    @Test(groups = {"regression", "e2e"}, description = "Scenario 2: declined payment cancels the order and leaves inventory untouched")
    public void verifyPaymentFailurePreventsInventoryReservation() {
        OrderContext context = OrderContext.builder()
                .customerId("CUST002")
                .productId("PROD001")
                .quantity(1)
                .paymentMethod("DECLINED_CARD")
                .build();

        OrderLifecycleHelper.builder()
                .orderContext(context)
                .flow(OrderLifecycleHelper.Flow.PAYMENT_FAILURE)
                .build()
                .test();
    }

    @Test(groups = {"regression", "e2e"}, description = "Scenario 3: ordering more than available stock is rejected without touching inventory")
    public void verifyInsufficientInventoryIsRejectedCleanly() {
        OrderContext context = OrderContext.builder()
                .customerId("CUST003")
                .productId("PROD-LOW-STOCK") // seeded at 5 units
                .quantity(10)
                .build();

        OrderLifecycleHelper.builder()
                .orderContext(context)
                .flow(OrderLifecycleHelper.Flow.INSUFFICIENT_INVENTORY)
                .build()
                .test();
    }
}
