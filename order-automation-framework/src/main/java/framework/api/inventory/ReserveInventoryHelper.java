package framework.api.inventory;

import framework.api.BaseHelper;
import framework.api.ServiceHelper;
import framework.builders.OrderRequestBuilder;
import framework.constants.Endpoints;
import framework.constants.StatusConstants;
import framework.context.OrderContext;
import framework.models.request.InventoryRequest;
import framework.models.response.ErrorResponse;
import framework.models.response.InventoryResponse;
import framework.utils.RestUtils;
import io.restassured.response.Response;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

/**
 * Calls POST /inventory/reserve. Like ProcessPaymentHelper, this serves
 * both the happy path and Scenario 3 (insufficient inventory) - the
 * expectation is derived from the quantity/product the context was built
 * with, rather than needing a parallel "helper for the failure case".
 */
@Builder
public class ReserveInventoryHelper extends BaseHelper implements ServiceHelper {

    private static final Logger log = LoggerFactory.getLogger(ReserveInventoryHelper.class);

    private final OrderContext orderContext;

    /** Set true by the test when it expects a 409 (Scenario 3). */
    @Builder.Default
    private final boolean expectInsufficientInventory = false;

    private InventoryRequest requestPayload;
    private InventoryResponse inventoryResponse;
    private ErrorResponse errorResponse;

    @Override
    public ServiceHelper init() {
        requestPayload = OrderRequestBuilder.buildInventoryRequest(orderContext);
        headers.put("Content-Type", "application/json");
        log.info("Reserving inventory for order={}, product={}, qty={}",
                orderContext.getOrderId(), orderContext.getProductId(), orderContext.getQuantity());
        return this;
    }

    @Override
    public ServiceHelper process() {
        Response response = RestUtils.post(
                Endpoints.RESERVE_INVENTORY.getPath(),
                headers,
                OrderRequestBuilder.toJson(requestPayload));
        captureResponse(response);
        return this;
    }

    @Override
    public ServiceHelper validate() {
        if (expectInsufficientInventory) {
            Assert.assertEquals(statusCode, 409, "Expected 409 when inventory is insufficient");
            errorResponse = response.as(ErrorResponse.class);
            Assert.assertEquals(errorResponse.getErrorCode(), "INSUFFICIENT_INVENTORY");
            log.info("Insufficient inventory correctly rejected for order={}", orderContext.getOrderId());
        } else {
            Assert.assertEquals(statusCode, 200, "Expected 200 when inventory reservation succeeds");
            inventoryResponse = response.as(InventoryResponse.class);
            Assert.assertEquals(inventoryResponse.getStatus(), StatusConstants.INVENTORY_STATUS_RESERVED);
            Assert.assertEquals(inventoryResponse.getQuantity(), orderContext.getQuantity());
            log.info("Inventory reserved for order={}", orderContext.getOrderId());
        }
        return this;
    }

    public InventoryResponse getInventoryResponse() {
        return inventoryResponse;
    }

    public ErrorResponse getErrorResponse() {
        return errorResponse;
    }
}
