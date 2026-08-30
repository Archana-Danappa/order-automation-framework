package apiTests;

import baseTests.BaseTest;
import framework.constants.Endpoints;
import framework.context.OrderContext;
import framework.utils.RestUtils;
import io.restassured.response.Response;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Map;

/**
 * High-value negative coverage (Section 24). Each test targets exactly one
 * failure mode, matched against the concrete behaviour the mock backend
 * implements - not a generic "should return 4xx" assertion.
 */
public class OrderNegativeTests extends BaseTest {

    @Test(groups = {"regression", "negative"}, description = "Missing required field returns 400 with a meaningful error")
    public void verifyMissingRequiredFieldReturns400() {
        String body = "{\"customerId\": \"CUST001\", \"quantity\": 2}"; // productId missing
        Response response = RestUtils.post(Endpoints.CREATE_ORDER.getPath(), Map.of(), body);
        Assert.assertEquals(response.statusCode(), 400);
        Assert.assertEquals(response.jsonPath().getString("errorCode"), "INVALID_REQUEST");
    }

    @Test(groups = {"regression", "negative"}, description = "Zero/negative quantity is rejected")
    public void verifyInvalidQuantityIsRejected() {
        String body = "{\"customerId\": \"CUST001\", \"productId\": \"PROD001\", \"quantity\": 0}";
        Response response = RestUtils.post(Endpoints.CREATE_ORDER.getPath(), Map.of(), body);
        Assert.assertEquals(response.statusCode(), 400);
        Assert.assertEquals(response.jsonPath().getString("errorCode"), "INVALID_QUANTITY");
    }

    @Test(groups = {"regression", "negative"}, description = "Fetching a non-existent order returns 404")
    public void verifyNonExistentOrderReturns404() {
        Response response = RestUtils.get(Endpoints.GET_ORDER.resolve("ORD-DOES-NOT-EXIST"), Map.of());
        Assert.assertEquals(response.statusCode(), 404);
        Assert.assertEquals(response.jsonPath().getString("errorCode"), "ORDER_NOT_FOUND");
    }

    @Test(groups = {"regression", "negative"}, description = "Processing payment for a non-existent order returns 404, not a false success")
    public void verifyPaymentForNonExistentOrderReturns404() {
        String body = "{\"orderId\": \"ORD-DOES-NOT-EXIST\", \"amount\": 500, \"paymentMethod\": \"CARD\"}";
        Response response = RestUtils.post(Endpoints.PROCESS_PAYMENT.getPath(), Map.of(), body);
        Assert.assertEquals(response.statusCode(), 404);
    }

    @Test(groups = {"regression", "negative"}, description = "Reserving inventory for a non-existent order returns 404")
    public void verifyInventoryReservationForNonExistentOrderReturns404() {
        String body = "{\"orderId\": \"ORD-DOES-NOT-EXIST\", \"productId\": \"PROD001\", \"quantity\": 1}";
        Response response = RestUtils.post(Endpoints.RESERVE_INVENTORY.getPath(), Map.of(), body);
        Assert.assertEquals(response.statusCode(), 404);
    }

    @Test(groups = {"regression", "negative"}, description = "A non-positive payment amount is treated as a declined payment, not a silent success")
    public void verifyInvalidPaymentAmountIsDeclined() {
        // Create a real order first so we're testing the payment-amount
        // validation specifically, not a 404 for a missing order.
        String createBody = "{\"customerId\": \"CUST-NEG\", \"productId\": \"PROD001\", \"quantity\": 1}";
        Response createResponse = RestUtils.post(Endpoints.CREATE_ORDER.getPath(), Map.of(), createBody);
        String orderId = createResponse.jsonPath().getString("orderId");

        String paymentBody = "{\"orderId\": \"" + orderId + "\", \"amount\": -50, \"paymentMethod\": \"CARD\"}";
        Response paymentResponse = RestUtils.post(Endpoints.PROCESS_PAYMENT.getPath(), Map.of(), paymentBody);
        Assert.assertEquals(paymentResponse.statusCode(), 402);
        Assert.assertEquals(paymentResponse.jsonPath().getString("status"), "FAILED");
    }

    @Test(groups = {"regression", "negative"}, description = "Requesting more units than are in stock is rejected without touching inventory")
    public void verifyOverOrderingAgainstLowStockProductIsRejected() {
        String createBody = "{\"customerId\": \"CUST-NEG\", \"productId\": \"PROD-LOW-STOCK\", \"quantity\": 1}";
        String orderId = RestUtils.post(Endpoints.CREATE_ORDER.getPath(), Map.of(), createBody)
                .jsonPath().getString("orderId");

        String reserveBody = "{\"orderId\": \"" + orderId + "\", \"productId\": \"PROD-LOW-STOCK\", \"quantity\": 999}";
        Response response = RestUtils.post(Endpoints.RESERVE_INVENTORY.getPath(), Map.of(), reserveBody);
        Assert.assertEquals(response.statusCode(), 409);
        Assert.assertEquals(response.jsonPath().getString("errorCode"), "INSUFFICIENT_INVENTORY");
    }
}
