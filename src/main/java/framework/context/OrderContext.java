package framework.context;

import lombok.Builder;
import lombok.Data;

/**
 * Carries data across the individual helpers that make up one business
 * flow (create order -> pay -> reserve inventory -> poll -> validate).
 *
 * One helper populates fields the next helper needs - e.g. CreateOrderHelper
 * fills in orderId, then ProcessPaymentHelper reads it back out - so state
 * flows through the context object rather than through constructor chains
 * or return-value threading between helpers.
 *
 * Defaults are intentionally set here (via @Builder.Default) so a test can
 * override only the field it cares about:
 *
 *   OrderContext.builder().paymentMethod("DECLINED_CARD").build()
 *
 * gets a fully valid order/payment context except for the one field the
 * test wants to break.
 */
@Data
@Builder
public class OrderContext {

    @Builder.Default
    private String customerId = "CUST001";

    @Builder.Default
    private String productId = "PROD001";

    @Builder.Default
    private int quantity = 2;

    @Builder.Default
    private double paymentAmount = 2000;

    @Builder.Default
    private String paymentMethod = "CARD";

    /** Optional - set on the context to test Scenario 4 (idempotency). */
    private String idempotencyKey;

    // Populated as the flow progresses - not supplied by the caller up front.
    private String orderId;
    private String paymentId;
    private String expectedFinalStatus;
}
