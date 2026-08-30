package integrationTests;

import baseTests.BaseTest;
import framework.api.order.CreateOrderHelper;
import framework.context.OrderContext;
import framework.models.response.OrderResponse;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Scenario 4: Duplicate Order / Idempotency.
 *
 * Two tests, deliberately different in kind:
 *  - the sequential replay test proves the *contract* (same key -> same
 *    order, no duplicate created);
 *  - the concurrent test proves the *implementation* actually holds up
 *    under a race, which a sequential test can never catch. Firing N
 *    requests with the same idempotency key at the same instant is the
 *    only way to exercise the check-then-act window in
 *    InMemoryBackendStore/handleCreateOrder.
 *
 * Java 21 virtual threads make firing genuinely concurrent HTTP calls from
 * a test cheap (no thread-pool tuning needed for a handful of concurrent
 * requests).
 */
public class OrderIdempotencyTest extends BaseTest {

    @Test(groups = {"regression"}, description = "Same idempotency key replayed sequentially returns the same order, not a duplicate")
    public void verifySequentialIdempotentReplayReturnsSameOrder() {
        String idempotencyKey = "TEST-ORDER-" + System.currentTimeMillis();
        OrderContext context = OrderContext.builder()
                .customerId("CUST-IDEMP")
                .productId("PROD001")
                .quantity(1)
                .idempotencyKey(idempotencyKey)
                .build();

        CreateOrderHelper firstCall = CreateOrderHelper.builder().orderContext(context).build();
        firstCall.test();
        String firstOrderId = firstCall.getOrderResponse().getOrderId();

        // Same key, same context - the *contract* under test - reuse a
        // fresh context with the orderId cleared so we're not just reading
        // stale local state.
        OrderContext replayContext = OrderContext.builder()
                .customerId("CUST-IDEMP")
                .productId("PROD001")
                .quantity(1)
                .idempotencyKey(idempotencyKey)
                .build();

        CreateOrderHelper replayCall = CreateOrderHelper.builder().orderContext(replayContext).build();
        replayCall.init().process(); // validate() below is custom, since a replay returns 200 not 201
        Assert.assertEquals(replayCall.getStatusCode(), 200, "Idempotent replay should return 200, not 201");

        OrderResponse replayResponse = replayCall.getResponse().as(OrderResponse.class);
        Assert.assertEquals(replayResponse.getOrderId(), firstOrderId,
                "Replaying the same idempotency key must return the original order, not create a new one");
    }

    @Test(groups = {"regression"}, description = "Concurrent requests with the same idempotency key must create exactly one order")
    public void verifyConcurrentRequestsWithSameKeyCreateExactlyOneOrder() throws InterruptedException {
        String idempotencyKey = "TEST-ORDER-CONCURRENT-" + System.currentTimeMillis();
        int concurrentRequests = 8;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = IntStream.range(0, concurrentRequests)
                    .mapToObj(i -> executor.submit(() -> {
                        OrderContext context = OrderContext.builder()
                                .customerId("CUST-CONCURRENT")
                                .productId("PROD001")
                                .quantity(1)
                                .idempotencyKey(idempotencyKey)
                                .build();
                        CreateOrderHelper helper = CreateOrderHelper.builder().orderContext(context).build();
                        helper.init().process();
                        return helper.getResponse().jsonPath().getString("orderId");
                    }))
                    .collect(Collectors.toList());

            List<String> orderIds = futures.stream().map(f -> {
                try {
                    return f.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList());

            long distinctOrderIds = orderIds.stream().distinct().count();
            Assert.assertEquals(distinctOrderIds, 1,
                    "All " + concurrentRequests + " concurrent requests with the same idempotency key " +
                            "must resolve to exactly one orderId, got: " + orderIds);
        }
    }
}
