package framework.api.payment;

import framework.api.BaseHelper;
import framework.api.ServiceHelper;
import framework.builders.OrderRequestBuilder;
import framework.constants.Endpoints;
import framework.constants.StatusConstants;
import framework.context.OrderContext;
import framework.models.request.PaymentRequest;
import framework.models.response.PaymentResponse;
import framework.utils.RestUtils;
import io.restassured.response.Response;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

/**
 * Calls POST /payments.
 *
 * Deliberately does NOT assume success: validate() branches on whether the
 * context was set up to simulate a decline (Scenario 2), so the same
 * helper serves both the happy-path and payment-failure tests instead of
 * needing a second near-duplicate helper class.
 */
@Builder
public class ProcessPaymentHelper extends BaseHelper implements ServiceHelper {

    private static final Logger log = LoggerFactory.getLogger(ProcessPaymentHelper.class);

    private final OrderContext orderContext;

    private PaymentRequest requestPayload;
    private PaymentResponse paymentResponse;

    @Override
    public ServiceHelper init() {
        requestPayload = OrderRequestBuilder.buildPaymentRequest(orderContext);
        headers.put("Content-Type", "application/json");
        log.info("Processing payment for order={}", orderContext.getOrderId());
        return this;
    }

    @Override
    public ServiceHelper process() {
        Response response = RestUtils.post(
                Endpoints.PROCESS_PAYMENT.getPath(),
                headers,
                OrderRequestBuilder.toJson(requestPayload));
        captureResponse(response);
        return this;
    }

    @Override
    public ServiceHelper validate() {
        boolean expectingFailure = "DECLINED_CARD".equalsIgnoreCase(orderContext.getPaymentMethod())
                || orderContext.getPaymentAmount() <= 0;

        if (expectingFailure) {
            Assert.assertEquals(statusCode, 402, "Expected 402 for a declined payment");
            paymentResponse = response.as(PaymentResponse.class);
            Assert.assertEquals(paymentResponse.getStatus(), StatusConstants.PAYMENT_STATUS_FAILED);
            log.info("Payment correctly failed for order={}", orderContext.getOrderId());
        } else {
            Assert.assertEquals(statusCode, 200, "Expected 200 for a successful payment");
            paymentResponse = response.as(PaymentResponse.class);
            Assert.assertEquals(paymentResponse.getStatus(), StatusConstants.PAYMENT_STATUS_SUCCESS);
            Assert.assertNotNull(paymentResponse.getPaymentId(), "paymentId should be present on success");
            orderContext.setPaymentId(paymentResponse.getPaymentId());
            log.info("Payment succeeded for order={}, paymentId={}", orderContext.getOrderId(), paymentResponse.getPaymentId());
        }
        return this;
    }

    public PaymentResponse getPaymentResponse() {
        return paymentResponse;
    }
}
