package framework.api.order;

import framework.api.BaseHelper;
import framework.api.ServiceHelper;
import framework.constants.Endpoints;
import framework.utils.RestUtils;
import io.restassured.response.Response;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

/**
 * Calls GET /orders/{orderId}/status. Used standalone in status-check tests,
 * and reused as the "check" callback inside PollingHelper for Scenario 5.
 */
@Builder
public class GetOrderStatusHelper extends BaseHelper implements ServiceHelper {

    private static final Logger log = LoggerFactory.getLogger(GetOrderStatusHelper.class);

    private final String orderId;
    private String currentStatus;

    @Override
    public ServiceHelper init() {
        log.info("Fetching status for order={}", orderId);
        return this;
    }

    @Override
    public ServiceHelper process() {
        Response response = RestUtils.get(Endpoints.GET_ORDER_STATUS.resolve(orderId), headers);
        captureResponse(response);
        return this;
    }

    @Override
    public ServiceHelper validate() {
        Assert.assertEquals(statusCode, 200, "Expected 200 when fetching order status");
        currentStatus = response.jsonPath().getString("status");
        return this;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }
}
