package framework.utils;

import framework.api.order.GetOrderStatusHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Generic polling utility (Section 10 - Scenario 5). Deliberately generic
 * over a Supplier<T>/Predicate<T> pair rather than hardcoded to order
 * status, so the same class can later poll a payment status, a shipment
 * status, or anything else async without being rewritten.
 *
 * Design choices worth calling out in the technical discussion:
 *  - Configurable interval and timeout (constructor args, not constants).
 *  - Polls on a fixed cadence rather than a single long Thread.sleep -
 *    this is what lets a test fail fast when the target state is reached
 *    early, and still allows genuinely slow async processing up to the
 *    timeout.
 *  - Timeout failure message includes the *last observed value*, not just
 *    "timed out", so a failing test tells you what state the system was
 *    actually stuck in.
 *  - This does not use a blind retry-on-exception strategy (Section 20) -
 *    it retries on "condition not yet true", which is a fundamentally
 *    different, intentional signal from "the call itself failed".
 */
public class PollingHelper<T> {

    private static final Logger log = LoggerFactory.getLogger(PollingHelper.class);

    private final Duration interval;
    private final Duration timeout;

    public PollingHelper(Duration interval, Duration timeout) {
        this.interval = interval;
        this.timeout = timeout;
    }

    public static PollingHelper<String> defaultOrderStatusPoller() {
        // Poll every 2s, max 30s - matches the example in Section 10,
        // but callers can construct their own instance with different
        // values for a slower/faster environment.
        return new PollingHelper<>(Duration.ofSeconds(2), Duration.ofSeconds(30));
    }

    /**
     * Polls `check` every `interval` until `condition` is true or `timeout`
     * elapses. Returns the last value produced by `check`.
     */
    public T pollUntil(Supplier<T> check, Predicate<T> condition, String description) {
        Instant deadline = Instant.now().plus(timeout);
        T lastValue = null;
        int attempt = 0;

        while (Instant.now().isBefore(deadline)) {
            attempt++;
            lastValue = check.get();
            log.info("Poll attempt {} for [{}] -> {}", attempt, description, lastValue);
            if (condition.test(lastValue)) {
                log.info("Poll condition met for [{}] after {} attempt(s): {}", description, attempt, lastValue);
                return lastValue;
            }
            sleep(interval);
        }

        throw new AssertionError(String.format(
                "Timed out after %ds waiting for [%s]. Last observed value: %s",
                timeout.getSeconds(), description, lastValue));
    }

    /** Convenience overload wired directly to order-status polling. */
    public static String pollOrderStatusUntil(String orderId, String expectedStatus) {
        PollingHelper<String> poller = defaultOrderStatusPoller();
        return poller.pollUntil(
                () -> {
                    GetOrderStatusHelper helper = GetOrderStatusHelper.builder().orderId(orderId).build();
                    helper.test();
                    return helper.getCurrentStatus();
                },
                status -> expectedStatus.equals(status),
                "order " + orderId + " reaching status " + expectedStatus);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Polling interrupted", e);
        }
    }
}
