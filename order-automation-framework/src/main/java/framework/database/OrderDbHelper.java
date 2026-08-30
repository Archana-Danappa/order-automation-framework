package framework.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reusable MySQL query + validation utility (Section 11). Every method
 * borrows a connection from DatabaseConnectionManager and closes it via
 * try-with-resources - callers never see a raw Connection/PreparedStatement.
 *
 * Schema assumed matches the assessment's suggested model:
 *   orders(order_id, customer_id, product_id, quantity, status, created_at)
 *   payments(payment_id, order_id, amount, status)
 *   inventory(product_id, available_quantity)
 */
public class OrderDbHelper {

    private static final Logger log = LoggerFactory.getLogger(OrderDbHelper.class);

    public OrderDbRecord fetchOrder(String orderId) {
        String sql = "SELECT order_id, customer_id, product_id, quantity, status FROM orders WHERE order_id = ?";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return OrderDbRecord.builder()
                        .orderId(rs.getString("order_id"))
                        .customerId(rs.getString("customer_id"))
                        .productId(rs.getString("product_id"))
                        .quantity(rs.getInt("quantity"))
                        .status(rs.getString("status"))
                        .build();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fetch order " + orderId + " from the database", e);
        }
    }

    public String fetchPaymentStatus(String orderId) {
        String sql = "SELECT status FROM payments WHERE order_id = ? ORDER BY payment_id DESC LIMIT 1";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("status") : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fetch payment status for order " + orderId, e);
        }
    }

    public Integer fetchAvailableInventory(String productId) {
        String sql = "SELECT available_quantity FROM inventory WHERE product_id = ?";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("available_quantity") : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fetch inventory for product " + productId, e);
        }
    }

    /**
     * Writes the "shadow" order/payment record a real backend would have
     * persisted. Needed because Option B (mock backend) has no real MySQL
     * behind it - the mock only holds state in-process. The test flow calls
     * this immediately after each mock API call succeeds, so the DB
     * validation layer is exercised against real MySQL rows rather than
     * against nothing. See OrderLifecycleHelper.
     *
     * Uses a portable check-then-insert-or-update instead of a
     * vendor-specific upsert (e.g. MySQL's ON DUPLICATE KEY UPDATE), so
     * the exact same SQL runs correctly against both the embedded H2
     * database and a real MySQL instance.
     */
    public void upsertOrder(OrderDbRecord record) {
        try (Connection conn = DatabaseConnectionManager.getConnection()) {
            boolean exists = fetchOrder(record.getOrderId()) != null;
            if (exists) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE orders SET status = ?, quantity = ? WHERE order_id = ?")) {
                    ps.setString(1, record.getStatus());
                    ps.setInt(2, record.getQuantity());
                    ps.setString(3, record.getOrderId());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO orders (order_id, customer_id, product_id, quantity, status, created_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, record.getOrderId());
                    ps.setString(2, record.getCustomerId());
                    ps.setString(3, record.getProductId());
                    ps.setInt(4, record.getQuantity());
                    ps.setString(5, record.getStatus());
                    ps.setTimestamp(6, java.sql.Timestamp.from(java.time.Instant.now()));
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert order " + record.getOrderId(), e);
        }
    }

    public void updateOrderStatus(String orderId, String status) {
        String sql = "UPDATE orders SET status = ? WHERE order_id = ?";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, orderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update status for order " + orderId, e);
        }
    }

    /** Same portable check-then-insert-or-update approach as upsertOrder(). */
    public void insertPayment(String paymentId, String orderId, double amount, String status) {
        String resolvedPaymentId = paymentId != null ? paymentId : "PAY-FAILED-" + orderId;
        try (Connection conn = DatabaseConnectionManager.getConnection()) {
            boolean exists;
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT COUNT(*) FROM payments WHERE payment_id = ?")) {
                check.setString(1, resolvedPaymentId);
                try (ResultSet rs = check.executeQuery()) {
                    rs.next();
                    exists = rs.getInt(1) > 0;
                }
            }
            if (exists) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE payments SET status = ? WHERE payment_id = ?")) {
                    ps.setString(1, status);
                    ps.setString(2, resolvedPaymentId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO payments (payment_id, order_id, amount, status) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, resolvedPaymentId);
                    ps.setString(2, orderId);
                    ps.setDouble(3, amount);
                    ps.setString(4, status);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert payment for order " + orderId, e);
        }
    }

    public void decrementInventory(String productId, int quantity) {
        String sql = "UPDATE inventory SET available_quantity = available_quantity - ? " +
                "WHERE product_id = ? AND available_quantity >= ?";
        try (Connection conn = DatabaseConnectionManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, quantity);
            ps.setString(2, productId);
            ps.setInt(3, quantity);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to decrement inventory for product " + productId, e);
        }
    }

    /**
     * Asserts the API-reported order state matches what's persisted -
     * the core cross-layer check the assessment asks for (Section 11:
     * "API Order ID = DB Order ID", "API Payment Status = DB Payment Status").
     */
    public void assertOrderMatchesDb(String orderId, String expectedStatus) {
        OrderDbRecord dbRecord = fetchOrder(orderId);
        Assert.assertNotNull(dbRecord, "Order " + orderId + " should exist in the database");
        Assert.assertEquals(dbRecord.getStatus(), expectedStatus,
                "DB order status should match the API-reported status for order " + orderId);
        log.info("DB validation passed for order={} status={}", orderId, expectedStatus);
    }

    /** Test data cleanup - deletes rows created by a test's orderId(s). */
    public void deleteOrder(String orderId) {
        String[] deletes = {
                "DELETE FROM payments WHERE order_id = ?",
                "DELETE FROM orders WHERE order_id = ?"
        };
        try (Connection conn = DatabaseConnectionManager.getConnection()) {
            for (String sql : deletes) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, orderId);
                    ps.executeUpdate();
                }
            }
            log.info("Cleaned up test data for order={}", orderId);
        } catch (SQLException e) {
            log.warn("Failed to clean up test data for order={}: {}", orderId, e.getMessage());
        }
    }
}
