package apiTests;

import baseTests.BaseTest;
import dataproviders.OrderDataProvider;
import framework.constants.Endpoints;
import framework.utils.RestUtils;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Map;

/**
 * One test method, four datasets (Section 17) - the point being that
 * validation logic lives once, in this method, rather than being
 * copy-pasted per scenario. See OrderDataProvider for the row shapes and
 * the documented "Invalid Product" simplification.
 */
public class OrderCreationDataDrivenTest extends BaseTest {

    @Test(dataProvider = "orderCreationDatasets", dataProviderClass = OrderDataProvider.class,
            groups = {"regression", "data-driven"})
    public void verifyOrderCreationAcrossDatasets(String description, String productId, int quantity,
                                                    String failsAtStage, String expectedErrorCode) {
        String createBody = String.format(
                "{\"customerId\": \"CUST-DD\", \"productId\": \"%s\", \"quantity\": %d}", productId, quantity);
        Response createResponse = RestUtils.post(Endpoints.CREATE_ORDER.getPath(), Map.of(), createBody);

        if ("CREATE".equals(failsAtStage)) {
            Assert.assertEquals(createResponse.statusCode(), 400, description + ": expected order creation to fail");
            Assert.assertEquals(createResponse.jsonPath().getString("errorCode"), expectedErrorCode, description);
            return;
        }

        Assert.assertEquals(createResponse.statusCode(), 201, description + ": order creation should succeed");
        String orderId = createResponse.jsonPath().getString("orderId");

        String reserveBody = String.format(
                "{\"orderId\": \"%s\", \"productId\": \"%s\", \"quantity\": %d}", orderId, productId, quantity);
        Response reserveResponse = RestUtils.post(Endpoints.RESERVE_INVENTORY.getPath(), Map.of(), reserveBody);

        if ("RESERVE".equals(failsAtStage)) {
            Assert.assertEquals(reserveResponse.statusCode(), 409, description + ": expected reservation to fail");
            Assert.assertEquals(reserveResponse.jsonPath().getString("errorCode"), expectedErrorCode, description);
        } else {
            Assert.assertEquals(reserveResponse.statusCode(), 200, description + ": expected reservation to succeed");
        }
    }
}
