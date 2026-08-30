package framework.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Applies src/test/resources/schema.sql against whatever connection
 * DatabaseConnectionManager provides. Called once, from BaseTest's
 * @BeforeSuite, and only when the active environment is embedded
 * (EnvironmentConfig.isEmbedded() - see dev.yaml) - a real, persistent
 * environment (qa.yaml, embedded: false) is expected to already have this
 * schema applied manually once, so an automated test run never touches a
 * real shared database's DDL.
 */
public final class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private SchemaInitializer() {
    }

    public static void applySchema() {
        try (Connection conn = DatabaseConnectionManager.getConnection();
             Statement statement = conn.createStatement()) {
            String script = readResource("/schema.sql");
            for (String rawStatement : script.split(";")) {
                String sql = rawStatement.trim();
                if (!sql.isEmpty()) {
                    statement.execute(sql);
                }
            }
            log.info("Embedded database schema applied successfully");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply embedded database schema from schema.sql", e);
        }
    }

    private static String readResource(String resourcePath) throws Exception {
        try (InputStream is = SchemaInitializer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("schema.sql not found on classpath at " + resourcePath +
                        " - expected at src/test/resources/schema.sql");
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().startsWith("--")) {
                        sb.append(line).append("\n");
                    }
                }
            }
            return sb.toString();
        }
    }
}
