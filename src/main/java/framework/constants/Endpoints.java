package framework.constants;

/**
 * Centralizes every API path so no test or helper ever hardcodes a URL
 * string. If a path changes, it changes here once.
 */
public enum Endpoints {

    CREATE_ORDER("/orders"),
    GET_ORDER("/orders/%s"),
    GET_ORDER_STATUS("/orders/%s/status"),
    PROCESS_PAYMENT("/payments"),
    RESERVE_INVENTORY("/inventory/reserve");

    private final String path;

    Endpoints(String path) {
        this.path = path;
    }

    /** For endpoints with no path variables. */
    public String getPath() {
        return path;
    }

    /** For endpoints containing %s placeholders, e.g. GET_ORDER.resolve(orderId). */
    public String resolve(Object... pathParams) {
        return String.format(path, pathParams);
    }
}
