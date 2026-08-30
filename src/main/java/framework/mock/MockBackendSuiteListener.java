package framework.mock;

import framework.database.DatabaseConnectionManager;
import framework.database.MongoConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ISuite;
import org.testng.ISuiteListener;

/**
 * Registered in testng.xml as a <listener>. Starts the in-process mock
 * backend before any test in the suite runs, and tears down the mock
 * server plus the MySQL/Mongo connection pools once the whole suite
 * finishes - so individual test classes never manage this lifecycle
 * themselves.
 */
public class MockBackendSuiteListener implements ISuiteListener {

    private static final Logger log = LoggerFactory.getLogger(MockBackendSuiteListener.class);
    private static final int MOCK_PORT = 8089;

    private MockOrderBackendServer mockServer;

    @Override
    public void onStart(ISuite suite) {
        mockServer = new MockOrderBackendServer(MOCK_PORT);
        mockServer.start();
        log.info("Suite [{}] started - mock backend listening on {}", suite.getName(), MOCK_PORT);
    }

    @Override
    public void onFinish(ISuite suite) {
        if (mockServer != null) {
            mockServer.stop();
        }
        DatabaseConnectionManager.shutdown();
        MongoConnectionManager.shutdown();
        log.info("Suite [{}] finished - mock backend and DB connections shut down", suite.getName());
    }
}
