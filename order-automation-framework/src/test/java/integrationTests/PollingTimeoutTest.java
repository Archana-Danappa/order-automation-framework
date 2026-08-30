package integrationTests;

import baseTests.BaseTest;
import framework.api.inventory.ReserveInventoryHelper;
import framework.api.order.CreateOrderHelper;
import framework.api.order.GetOrderStatusHelper;
import framework.api.payment.ProcessPaymentHelper;
import framework.context.OrderContext;
import framework.utils.PollingHelper;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Duration;

/**
 * Section 24, negative test #10 - "Asynchronous processing timeout".
 *
 * Every other use of PollingHelper in this suite exercises its happy path
 * (the condition is met before the timeout). This test exercises the
 * timeout path itself: the mock backend takes ~12 seconds after inventory
 * reservation to reach DELIVERED (see MockOrderBackendServer's
 * SHIPPED_TO_DELIVERED constant), so polling for DELIVERED with a
 * deliberately short 2-second timeout is guaranteed to time out. This is
 * what proves PollingHelper fails loudly and informatively - not by
 * hanging, and not by silently treating an intermediate status as success.
 */
public class PollingTimeoutTest extends BaseTest {

    @Test(groups = {"regression", "negative"},
            description = "PollingHelper times out with a clear message (including the last observed value) " +
                    "when a status is not reached within the configured timeout")
    public void verifyPollingTimesOutWithMeaningfulMessage() {
        OrderContext context = OrderContext.builder()
                .customerId("CUST-TIMEOUT")
                .productId("PROD001")
                .quantity(1)
                .build();

        CreateOrderHelper.builder().orderContext(context).build().test();
        ProcessPaymentHelper.builder().orderContext(context).build().test();
        ReserveInventoryHelper.builder().orderContext(context).build().test();

        // Order has just entered the async PROCESSING stage. DELIVERED is
        // ~12s away - a 2s timeout must fail, not hang and not silently
        // return an intermediate status as if it were the target one.
        PollingHelper<String> shortTimeoutPoller = new PollingHelper<>(Duration.ofSeconds(1), Duration.ofSeconds(2));

        try {
            shortTimeoutPoller.pollUntil(
                    () -> {
                        GetOrderStatusHelper helper = GetOrderStatusHelper.builder().orderId(context.getOrderId()).build();
                        helper.test();
                        return helper.getCurrentStatus();
                    },
                    status -> "DELIVERED".equals(status),
                    "order " + context.getOrderId() + " reaching status DELIVERED");
            Assert.fail("Expected PollingHelper to time out before DELIVERED was reached, but it returned normally");
        } catch (AssertionError e) {
            Assert.assertTrue(e.getMessage().contains("Timed out"),
                    "Timeout failure message should clearly state it timed out. Actual: " + e.getMessage());
            Assert.assertTrue(e.getMessage().contains("Last observed value"),
                    "Timeout failure message should report the last observed value, not just 'timed out'. " +
                            "A test that only says 'it failed' gives an engineer nothing to start debugging from. Actual: "
                            + e.getMessage());
        }
    }
}
