package framework.database;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import framework.config.ConfigLoader;
import framework.config.EnvironmentConfig;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Owns a single MongoClient for the run, mirroring
 * DatabaseConnectionManager's role for MySQL.
 *
 * Configured with a short (2s) server-selection/connect timeout rather
 * than the driver's default (30s) - this is what makes
 * OrderEventHelper's "is Mongo actually reachable" probe fail fast
 * instead of stalling every test run for 30 seconds before falling back
 * to the embedded event store.
 */
public final class MongoConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(MongoConnectionManager.class);
    private static volatile MongoClient client;

    private MongoConnectionManager() {
    }

    private static synchronized MongoClient getClient() {
        if (client == null) {
            EnvironmentConfig.MongoConfig mongoConfig = ConfigLoader.get().getMongo();
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(mongoConfig.getConnectionString()))
                    .applyToClusterSettings(builder -> builder.serverSelectionTimeout(2, TimeUnit.SECONDS))
                    .applyToSocketSettings(builder -> builder.connectTimeout(2, TimeUnit.SECONDS))
                    .build();
            client = MongoClients.create(settings);
            log.info("Initialized MongoDB client for {}", mongoConfig.getConnectionString());
        }
        return client;
    }

    public static MongoCollection<Document> getEventsCollection() {
        EnvironmentConfig.MongoConfig mongoConfig = ConfigLoader.get().getMongo();
        MongoDatabase database = getClient().getDatabase(mongoConfig.getDatabase());
        return database.getCollection(mongoConfig.getEventsCollection());
    }

    public static synchronized void shutdown() {
        if (client != null) {
            client.close();
            client = null;
            log.info("MongoDB client shut down");
        }
    }
}
