package framework.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * Section 20 - test reliability without blind retries.
 *
 * This is intentionally narrow: it retries a test ONLY when the failure's
 * root cause is a small, explicit allow-list of transient network
 * conditions (connection reset/refused, socket timeout). It does NOT
 * retry on:
 *  - AssertionError (a genuine business-logic failure - retrying would
 *    hide a real bug behind a "passed on retry" report)
 *  - Any other RuntimeException we haven't explicitly classified as
 *    transient
 *
 * Async-in-progress conditions (Scenario 5) are deliberately NOT handled
 * here at all - those go through PollingHelper, which polls the same
 * request until a condition is met or a timeout elapses with a clear
 * failure message. Conflating "the API call itself failed" with "the
 * system hasn't finished processing yet" is exactly the trap Section 20
 * warns against, so the two are kept as separate mechanisms rather than
 * one generic retry-everything wrapper.
 */
public class TransientFailureRetryAnalyzer implements IRetryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TransientFailureRetryAnalyzer.class);
    private static final int MAX_RETRIES = 2;

    private int retryCount = 0;

    @Override
    public boolean retry(ITestResult result) {
        if (retryCount >= MAX_RETRIES) {
            return false;
        }
        Throwable cause = result.getThrowable();
        if (isTransient(cause)) {
            retryCount++;
            log.warn("Retrying [{}] (attempt {}/{}) after transient failure: {}",
                    result.getName(), retryCount, MAX_RETRIES, cause.getMessage());
            return true;
        }
        return false;
    }

    private boolean isTransient(Throwable cause) {
        if (cause == null) {
            return false;
        }
        // Never retry a genuine assertion failure - that is the test doing
        // its job correctly.
        if (cause instanceof AssertionError) {
            return false;
        }
        Throwable rootCause = rootCause(cause);
        return rootCause instanceof SocketTimeoutException
                || rootCause instanceof ConnectException
                || (rootCause.getMessage() != null && rootCause.getMessage().contains("Connection reset"));
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
