package framework.api;

import io.restassured.response.Response;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Generic parent for every helper class. Holds the fields every helper
 * needs regardless of which API it calls: headers, the raw REST Assured
 * response, the extracted status code, and a per-helper correlation ID.
 *
 * The correlation ID is generated in the constructor and attached to the
 * SLF4J MDC so every log line emitted while this helper runs - including
 * lines from RestUtils, DB utils, and Mongo utils further down the call
 * stack - carries the same ID. That is what makes the "200 returned but DB
 * has stale data" debugging scenario (Section 30) tractable: grep the
 * correlation ID across API/DB/event logs and see exactly where the
 * divergence happened, instead of reading three unrelated log streams.
 */
public abstract class BaseHelper {

    protected Map<String, String> headers = new HashMap<>();
    protected Response response;
    protected int statusCode;
    protected final String correlationId;

    protected BaseHelper() {
        this.correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
    }

    protected void captureResponse(Response response) {
        this.response = response;
        this.statusCode = response.statusCode();
    }

    public Response getResponse() {
        return response;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
