package framework.constants;

/**
 * Centralized status literals so tests never compare against raw strings
 * scattered across the codebase (avoids typos like "CONFIRM" vs "CONFIRMED").
 */
public final class StatusConstants {

    private StatusConstants() {
    }

    // Order statuses
    public static final String CREATED = "CREATED";
    public static final String PAYMENT_SUCCESS = "PAYMENT_SUCCESS";
    public static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String PROCESSING = "PROCESSING";
    public static final String SHIPPED = "SHIPPED";
    public static final String DELIVERED = "DELIVERED";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";

    // Payment statuses
    public static final String PAYMENT_STATUS_SUCCESS = "SUCCESS";
    public static final String PAYMENT_STATUS_FAILED = "FAILED";

    // Inventory statuses
    public static final String INVENTORY_STATUS_RESERVED = "RESERVED";

    // Mongo event types
    public static final String EVENT_ORDER_CREATED = "ORDER_CREATED";
    public static final String EVENT_PAYMENT_SUCCESS = "PAYMENT_SUCCESS";
    public static final String EVENT_INVENTORY_RESERVED = "INVENTORY_RESERVED";
    public static final String EVENT_ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String EVENT_ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String EVENT_ORDER_DELIVERED = "ORDER_DELIVERED";
}
