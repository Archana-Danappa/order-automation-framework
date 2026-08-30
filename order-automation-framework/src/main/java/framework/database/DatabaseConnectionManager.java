package framework.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import framework.config.ConfigLoader;
import framework.config.EnvironmentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owns a single pooled DataSource for the whole test run - either the
 * embedded H2 database (dev.yaml, zero setup) or a real MySQL instance
 * (qa.yaml), depending on which environment's jdbcUrl is configured.
 * HikariCP resolves the correct JDBC driver from the URL itself, so no
 * driver class is hardcoded here. Every DB util calls
 * getConnection()/borrow-return through here rather than opening its own
 * java.sql.Connection, so pool sizing, cleanup, and credential resolution
 * happen in exactly one place (Section 11 - connection cleanup, reusable
 * DB utilities).
 */
public final class DatabaseConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionManager.class);
    private static volatile HikariDataSource dataSource;

    private DatabaseConnectionManager() {
    }

    public static synchronized Connection getConnection() throws SQLException {
        if (dataSource == null) {
            EnvironmentConfig.MySqlConfig mysqlConfig = ConfigLoader.get().getMysql();
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(mysqlConfig.getJdbcUrl());
            hikariConfig.setUsername(mysqlConfig.getUsername());
            hikariConfig.setPassword(mysqlConfig.getPassword());
            hikariConfig.setMaximumPoolSize(5);
            hikariConfig.setPoolName("order-automation-pool");
            dataSource = new HikariDataSource(hikariConfig);
            log.info("Initialized DB connection pool for {}", mysqlConfig.getJdbcUrl());
        }
        return dataSource.getConnection();
    }

    /** Call once at suite teardown to release the pool cleanly. */
    public static synchronized void shutdown() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            log.info("DB connection pool shut down");
        }
    }
}
