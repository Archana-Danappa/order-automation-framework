package framework.database;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable event-validation utility (Section 12).
 *
 * Tries real MongoDB first (the configured connectionString - see
 * MongoConnectionManager). If Mongo is unreachable, transparently falls
 * back to an H2-backed "events" table living in the same embedded database
 * used for MySQL validation (see schema.sql / SchemaInitializer), so event
 * sequence validation genuinely runs on a fresh checkout with zero external
 * setup - `git clone` + `mvn test`, nothing else - while still exercising
 * real MongoDB whenever it's actually available (e.g. a real qa environment,
 * or a developer machine that happens to have Mongo running locally).
 *
 * The availability check is probed once per JVM run (not per call) with a
 * fast 2-second timeout (see MongoConnectionManager), so a missing Mongo
 * instance costs one short pause at the very first event call, not a delay
 * on every single assertion.
 */
public class OrderEventHelper {

    private static final Logger log = LoggerFactory.getLogger(OrderEventHelper.class);
    private static volatile Boolean mongoAvailable;

    private boolean isMongoAvailable() {
        if (mongoAvailable == null) {
            synchronized (OrderEventHelper.class) {
                if (mongoAvailable == null) {
                    try {
                        MongoConnectionManager.getEventsCollection().estimatedDocumentCount();
                        mongoAvailable = true;
                        log.info("MongoDB reachable - event validation will use real MongoDB");
                    } catch (Exception e) {
                        mongoAvailable = false;
                        log.warn("MongoDB not reachable ({}) - falling back to the embedded H2-backed " +
                                        "event store for this run. Event-sequence assertions still run for " +
                                        "real, just against a different backing store. See README for real " +
                                        "MongoDB setup.",
                                e.getMessage());
                    }
                }
            }
        }
        return mongoAvailable;
    }

    public List<String> fetchEventSequence(String orderId) {
        return isMongoAvailable() ? fetchFromMongo(orderId) : fetchFromFallback(orderId);
    }

    public void insertEvent(String orderId, String eventType) {
        if (isMongoAvailable()) {
            insertIntoMongo(orderId, eventType);
        } else {
            insertIntoFallback(orderId, eventType);
        }
    }

    public void deleteEvents(String orderId) {
        if (isMongoAvailable()) {
            MongoConnectionManager.getEventsCollection().deleteMany(Filters.eq("orderId", orderId));
        } else {
            deleteFromFallback(orderId);
        }
        log.info("Cleaned up events for order={}", orderId);
    }

    /**
     * Asserts the events recorded for an order appear in exactly the given
     * order (a subsequence check would hide a missing or reordered event,
     * so this checks the full expected sequence explicitly).
     */
    public void assertEventSequence(String orderId, List<String> expectedEventsInOrder) {
        List<String> actualEvents = fetchEventSequence(orderId);
        Assert.assertEquals(actualEvents, expectedEventsInOrder,
                "Event sequence for order " + orderId + " did not match expected order");
        log.info("Event sequence validated for order={}: {}", orderId, actualEvents);
    }

    public void assertEventExists(String orderId, String eventType) {
        List<String> actualEvents = fetchEventSequence(orderId);
        Assert.assertTrue(actualEvents.contains(eventType),
                "Expected event " + eventType + " not found for order " + orderId + ". Found: " + actualEvents);
    }

    // ---------------------------------------------------------------
    // Real MongoDB path
    // ---------------------------------------------------------------

    private List<String> fetchFromMongo(String orderId) {
        MongoCollection<Document> collection = MongoConnectionManager.getEventsCollection();
        FindIterable<Document> results = collection
                .find(Filters.eq("orderId", orderId))
                .sort(Sorts.ascending("timestamp"));
        List<String> events = new ArrayList<>();
        for (Document doc : results) {
            events.add(doc.getString("event"));
        }
        return events;
    }

    private void insertIntoMongo(String orderId, String eventType) {
        MongoCollection<Document> collection = MongoConnectionManager.getEventsCollection();
        Document event = new Document("orderId", orderId)
                .append("event", eventType)
                .append("timestamp", Instant.now().toString());
        collection.insertOne(event);
        log.info("Recorded event {} for order={} (MongoDB)", eventType, orderId);
    }

    // ---------------------------------------------------------------
    // Embedded H2 fallback path - see schema.sql's `events` table
    // ---------------------------------------------------------------

    private List<String> fetchFromFallback(String orderId) {
        String sql = "SELECT event_type FROM events WHERE order_id = ? ORDER BY id ASC";
        List<String> events = new ArrayList<>();
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(rs.getString("event_type"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fetch fallback event sequence for order " + orderId, e);
        }
        return events;
    }

    private void insertIntoFallback(String orderId, String eventType) {
        String sql = "INSERT INTO events (order_id, event_type, event_timestamp) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderId);
            ps.setString(2, eventType);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
            log.info("Recorded event {} for order={} (H2 fallback)", eventType, orderId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record fallback event for order " + orderId, e);
        }
    }

    private void deleteFromFallback(String orderId) {
        String sql = "DELETE FROM events WHERE order_id = ?";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to clean up fallback events for order={}: {}", orderId, e.getMessage());
        }
    }
}
