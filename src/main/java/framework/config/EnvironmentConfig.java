package framework.config;

import lombok.Data;

/**
 * POJO mirroring one environment's YAML block (dev.yaml / qa.yaml).
 * Nothing environment-specific is ever hardcoded in test/helper code -
 * everything reads through this object via ConfigLoader.
 */
@Data
public class EnvironmentConfig {

    private String baseUrl;
    private int requestTimeoutMs;

    /**
     * True for environments (dev) that should use the embedded, zero-setup
     * H2 database and H2-backed event-store fallback instead of requiring
     * a real MySQL/MongoDB instance. False for environments (qa) that
     * point at real, persistent, already-provisioned datastores - those
     * are never auto-schema'd by the test run.
     */
    private boolean embedded;

    private MySqlConfig mysql;
    private MongoConfig mongo;
    private RedisConfig redis;

    @Data
    public static class MySqlConfig {
        private String jdbcUrl;
        private String username;
        private String password;
    }

    @Data
    public static class MongoConfig {
        private String connectionString;
        private String database;
        private String eventsCollection;
    }

    @Data
    public static class RedisConfig {
        private String host;
        private int port;
    }
}
