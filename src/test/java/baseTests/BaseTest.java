package baseTests;

import framework.config.ConfigLoader;
import framework.database.DatabaseConnectionManager;
import framework.database.MongoConnectionManager;
import framework.database.SchemaInitializer;
import framework.mock.MockOrderBackendServer;
import framework.reporting.ExtentReportListener;
import framework.reporting.RetryAnnotationTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Listeners;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every test class extends this instead of relying on listeners declared
 * only in testng.xml's <listeners> block.
 *
 * Why: any listener registered only in <listeners> (an ISuiteListener,
 * ITestListener, or IAnnotationTransformer) only fires when TestNG runs
 * *through* that suite XML. Running a single test method directly from an
 * IDE (right-click -> Run) makes the IDE construct its own ad-hoc XmlSuite
 * that never reads testng.xml, so none of those listeners run - the mock
 * backend never starts (every API call fails with Connection refused),
 * and no Extent report ever gets written, with no error to explain why.
 *
 * The fix is the same in both cases: @BeforeSuite/@AfterSuite and
 * @Listeners on a shared base class are honored by TestNG for whatever
 * suite actually gets constructed, ad-hoc or not, as long as the running
 * class extends this one - so ExtentReportListener and
 * RetryAnnotationTransformer are declared here instead of in testng.xml.
 *
 * Also applies the DB schema automatically when the active environment is
 * embedded (dev.yaml - see EnvironmentConfig.isEmbedded()), so a fresh
 * checkout requires zero external MySQL/MongoDB setup: `mvn test` just
 * works. A real, persistent environment (qa.yaml, embedded: false) is
 * expected to already have its schema applied manually once, so this
 * never auto-runs DDL against a real shared database.
 *
 * The AtomicBoolean guard exists because @BeforeSuite methods inherited
 * from a common base can otherwise fire once per subclass present in a
 * suite - the guard makes start/stop idempotent regardless of how many
 * test classes are in the run.
 */
@Listeners({ExtentReportListener.class, RetryAnnotationTransformer.class})
public abstract class BaseTest {

    private static final Logger log = LoggerFactory.getLogger(BaseTest.class);
    private static final int MOCK_PORT = 8089;
    private static final AtomicBoolean started = new AtomicBoolean(false);

    private static MockOrderBackendServer mockServer;

    @BeforeSuite(alwaysRun = true)
    public static synchronized void startMockBackend() {
        if (started.compareAndSet(false, true)) {
            mockServer = new MockOrderBackendServer(MOCK_PORT);
            mockServer.start();
            log.info("Mock backend started on port {} (BaseTest @BeforeSuite)", MOCK_PORT);

            if (ConfigLoader.get().isEmbedded()) {
                SchemaInitializer.applySchema();
            } else {
                log.info("Environment is not embedded - skipping automatic schema application " +
                        "(expected to already exist on the target database)");
            }
        }
    }

    @AfterSuite(alwaysRun = true)
    public static synchronized void stopMockBackend() {
        if (mockServer != null) {
            mockServer.stop();
        }
        DatabaseConnectionManager.shutdown();
        MongoConnectionManager.shutdown();
        started.set(false);
        log.info("Mock backend and DB connections shut down (BaseTest @AfterSuite)");
    }
}
