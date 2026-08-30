-- Portable DDL (works unchanged against embedded H2 in MySQL mode and
-- against a real MySQL instance) - no CREATE DATABASE/USE statements,
-- since the target database is already selected via the JDBC URL itself
-- in both cases. For a REAL MySQL setup, create the database once first:
--   mysql -u root -p -e "CREATE DATABASE order_management_dev;"
-- then point mysql.jdbcUrl in your environment's YAML at it.
--
-- Applied automatically at suite startup for embedded environments
-- (see SchemaInitializer / EnvironmentConfig.isEmbedded()). For real,
-- persistent environments (embedded: false), run this manually once.

CREATE TABLE IF NOT EXISTS orders (
    order_id     VARCHAR(64) PRIMARY KEY,
    customer_id  VARCHAR(64) NOT NULL,
    product_id   VARCHAR(64) NOT NULL,
    quantity     INT NOT NULL,
    status       VARCHAR(32) NOT NULL,
    created_at   TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS payments (
    payment_id   VARCHAR(64) PRIMARY KEY,
    order_id     VARCHAR(64) NOT NULL,
    amount       DECIMAL(10,2) NOT NULL,
    status       VARCHAR(32) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(order_id)
);

CREATE TABLE IF NOT EXISTS inventory (
    product_id         VARCHAR(64) PRIMARY KEY,
    available_quantity INT NOT NULL
);

-- Fallback event store, used only when MongoDB is unreachable at test
-- time (see OrderEventHelper). Identical shape to what would otherwise
-- be a MongoDB document: order_id / event_type / timestamp.
CREATE TABLE IF NOT EXISTS events (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    order_id        VARCHAR(64) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    event_timestamp VARCHAR(64) NOT NULL
);

-- Seed data, kept portable/re-runnable: delete-then-insert instead of a
-- vendor-specific upsert, so this script is safe to run more than once
-- against a real, persistent MySQL instance too.
DELETE FROM inventory WHERE product_id IN ('PROD001', 'PROD002', 'PROD-LOW-STOCK');
INSERT INTO inventory (product_id, available_quantity) VALUES
    ('PROD001', 100),
    ('PROD002', 50),
    ('PROD-LOW-STOCK', 5);
