package framework.reporting;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Section 22 - reporting. Captures test name, status, failure details, and
 * duration for every test in the suite and writes a single HTML report
 * under target/extent-reports/. Declared via @Listeners on baseTests.BaseTest
 * (not in testng.xml's <listeners>) so it fires whether the suite runs
 * through testng.xml or as a single ad-hoc method from an IDE.
 *
 * Sensitive data is never at risk here because it was already masked at
 * the source (RestUtils.safeHeaders) before it reached any log line this
 * report might reference - the report itself does not re-read raw
 * request/response bodies.
 */
public class ExtentReportListener implements ITestListener {

    private static final Logger log = LoggerFactory.getLogger(ExtentReportListener.class);

    private static ExtentReports extent;
    private static String reportAbsolutePath;
    private static final Map<Long, ExtentTest> testMap = new ConcurrentHashMap<>();
    private static final AtomicBoolean pathAnnounced = new AtomicBoolean(false);

    private static synchronized ExtentReports getExtent() {
        if (extent == null) {
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now());
            String relativePath = "target/extent-reports/report-" + timestamp + ".html";
            reportAbsolutePath = new File(relativePath).getAbsolutePath();

            ExtentSparkReporter reporter = new ExtentSparkReporter(relativePath);
            reporter.config().setDocumentTitle("Order & Warehouse Automation Report");
            reporter.config().setReportName("Backend API Automation - Test Execution Report");
            extent = new ExtentReports();
            extent.attachReporter(reporter);
            extent.setSystemInfo("Environment", System.getProperty("env", "dev"));
        }
        return extent;
    }

    @Override
    public void onTestStart(ITestResult result) {
        ExtentTest test = getExtent().createTest(
                result.getMethod().getMethodName(),
                result.getMethod().getDescription());
        testMap.put(Thread.currentThread().threadId(), test);
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        currentTest().log(Status.PASS, "Test passed in " + duration(result) + "ms");
    }

    @Override
    public void onTestFailure(ITestResult result) {
        currentTest().log(Status.FAIL, "Test failed in " + duration(result) + "ms");
        currentTest().fail(result.getThrowable());
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        currentTest().log(Status.SKIP, "Test skipped: " +
                (result.getThrowable() != null ? result.getThrowable().getMessage() : "no reason given"));
    }

    @Override
    public void onFinish(ITestContext context) {
        getExtent().flush();
        // Printed loudly and at INFO (not just left to be inferred from the
        // relative path in ExtentSparkReporter's own internal logging) -
        // this is the single most commonly "missed" output of a whole run.
        if (pathAnnounced.compareAndSet(false, true)) {
            log.info("=================================================================");
            log.info("Extent HTML report written to: {}", reportAbsolutePath);
            log.info("Open that file in a browser to view the test execution report.");
            log.info("=================================================================");
        }
    }

    private ExtentTest currentTest() {
        return testMap.get(Thread.currentThread().threadId());
    }

    private long duration(ITestResult result) {
        return result.getEndMillis() - result.getStartMillis();
    }
}

