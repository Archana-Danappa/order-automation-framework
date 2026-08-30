package framework.api.order;

import framework.api.BaseHelper;
import framework.api.ServiceHelper;
import framework.builders.OrderRequestBuilder;
import framework.constants.Endpoints;
import framework.context.OrderContext;
import framework.models.request.OrderRequest;
import framework.models.response.OrderResponse;
import framework.utils.RestUtils;
import io.restassured.response.Response;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

/**
 * Calls POST /orders.
 *
 * Overrides init()/process()/validate() from ServiceHelper - this is the
 * method-overriding + runtime-polymorphism point: every helper in this
 * package implements the same three methods differently, and callers only
 * ever hold a ServiceHelper reference.
 */
@Builder
public class CreateOrderHelper extends BaseHelper implements ServiceHelper {

    private static final Logger log = LoggerFactory.getLogger(CreateOrderHelper.class);

    private final OrderContext orderContext;

    private OrderRequest requestPayload;
    private OrderResponse orderResponse;

    @Override
    public ServiceHelper init() {
        requestPayload = OrderRequestBuilder.buildOrderRequest(orderContext);
        headers.put("Content-Type", "application/json");
        if (orderContext.getIdempotencyKey() != null) {
            headers.put("Idempotency-Key", orderContext.getIdempotencyKey());
        }
        log.info("Creating order for customer={}", orderContext.getCustomerId());
        return this;
    }

    @Override
    public ServiceHelper process() {
        Response response = RestUtils.post(
                Endpoints.CREATE_ORDER.getPath(),
                headers,
                OrderRequestBuilder.toJson(requestPayload));
        captureResponse(response);
        return this;
    }

    @Override
    public ServiceHelper validate() {
        Assert.assertEquals(statusCode, 201, "Expected 201 Created for a valid order request");
        orderResponse = response.as(OrderResponse.class);
        Assert.assertNotNull(orderResponse.getOrderId(), "orderId should not be null");
        Assert.assertEquals(orderResponse.getStatus(), "CREATED");
        Assert.assertEquals(orderResponse.getCustomerId(), orderContext.getCustomerId());
        Assert.assertEquals(orderResponse.getProductId(), orderContext.getProductId());
        Assert.assertEquals(orderResponse.getQuantity(), orderContext.getQuantity());

        // Feed the generated orderId back into the shared context so the
        // next helper in the flow (payment, inventory) can use it.
        orderContext.setOrderId(orderResponse.getOrderId());
        log.info("Order created and validated: {}", orderResponse.getOrderId());
        return this;
    }

    public OrderResponse getOrderResponse() {
        return orderResponse;
    }
}
