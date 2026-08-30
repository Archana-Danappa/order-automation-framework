-- Schema for the Order & Warehouse Management assessment.
-- Run this against a local MySQL instance before executing the suite
-- (see README "Database Setup"). Seed inventory values intentionally
-- mirror InMemoryBackendStore's seed values so the mock API layer and the
-- "shadow" DB layer start from the same numbers.

CREATE DATABASE IF NOT EXISTS order_management_dev;
USE order_management_dev;

CREATE TABLE IF NOT EXISTS orders (
    order_id     VARCHAR(64) PRIMARY KEY,
    customer_id  VARCHAR(64) NOT NULL,
    product_id   VARCHAR(64) NOT NULL,
    quantity     INT NOT NULL,
    status       VARCHAR(32) NOT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
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

INSERT INTO inventory (product_id, available_quantity) VALUES
    ('PROD001', 100),
    ('PROD002', 50),
    ('PROD-LOW-STOCK', 5)
ON DUPLICATE KEY UPDATE available_quantity = VALUES(available_quantity);
