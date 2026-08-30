package framework.builders;

import com.fasterxml.jackson.databind.ObjectMapper;
import framework.context.OrderContext;
import framework.models.request.InventoryRequest;
import framework.models.request.OrderRequest;
import framework.models.request.PaymentRequest;

/**
 * Translates an OrderContext into the actual request payloads the mock
 * backend expects. Kept separate from the POJOs' own Lombok builders on
 * purpose: OrderRequest.builder() is generic "set any field" plumbing,
 * whereas this class encodes the business rule of *which* context fields
 * map to *which* request, so that rule lives in one place instead of being
 * repeated inline in every helper's init().
 */
public final class OrderRequestBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrderRequestBuilder() {
    }

    public static OrderRequest buildOrderRequest(OrderContext context) {
        return OrderRequest.builder()
                .customerId(context.getCustomerId())
                .productId(context.getProductId())
                .quantity(context.getQuantity())
                .build();
    }

    public static PaymentRequest buildPaymentRequest(OrderContext context) {
        return PaymentRequest.builder()
                .orderId(context.getOrderId())
                .amount(context.getPaymentAmount())
                .paymentMethod(context.getPaymentMethod())
                .build();
    }

    public static InventoryRequest buildInventoryRequest(OrderContext context) {
        return InventoryRequest.builder()
                .orderId(context.getOrderId())
                .productId(context.getProductId())
                .quantity(context.getQuantity())
                .build();
    }

    public static String toJson(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize request payload: " + payload, e);
        }
    }
}
