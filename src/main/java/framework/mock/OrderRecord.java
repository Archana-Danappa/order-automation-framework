package framework.mock;

import lombok.Data;

import java.time.Instant;

/**
 * In-memory representation of an order inside the mock backend.
 * This is intentionally simple - it exists to give the WireMock extensions
 * something stateful to mutate, so the mock behaves like a real backend
 * (status transitions, inventory arithmetic) instead of returning canned JSON.
 */
@Data
public class OrderRecord {

    private String orderId;
    private String customerId;
    private String productId;
    private int quantity;
    private String status; // CREATED, PAYMENT_SUCCESS, FAILED, INVENTORY_RESERVED, PROCESSING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED

    private Instant createdAt;
    private Instant paymentAt;
    private Instant inventoryReservedAt;

    private String paymentId;
    private String idempotencyKey;
}
