package dataproviders;

import org.testng.annotations.DataProvider;

/**
 * Section 17 - data-driven testing, using the assessment's own named
 * examples (Valid Order / Invalid Product / Insufficient Inventory) plus
 * one extra (Invalid Quantity).
 *
 * Row shape: {description, productId, quantity, failsAtStage, expectedErrorCode}
 *   failsAtStage is one of "NONE", "CREATE", "RESERVE" - since different
 *   invalid inputs are rejected at different points in the pipeline, not
 *   all at the same API call. Encoding that explicitly avoids a test method
 *   that has to guess which call should have failed.
 *
 * Note on "Invalid Product": the mock backend has no separate product
 * catalog, so an unrecognized productId is not rejected with its own error
 * code - it simply has zero seeded stock, so reserving any quantity for it
 * surfaces as INSUFFICIENT_INVENTORY. This is documented rather than
 * silently treated as a pass/fail mismatch - a real backend would likely
 * return a distinct PRODUCT_NOT_FOUND, which is called out as a documented
 * simplification in the README.
 */
public class OrderDataProvider {

    @DataProvider(name = "orderCreationDatasets")
    public static Object[][] orderCreationDatasets() {
        return new Object[][]{
                {"Valid order", "PROD001", 2, "NONE", null},
                {"Invalid product (unrecognized productId, zero seeded stock)", "PROD-UNKNOWN", 1, "RESERVE", "INSUFFICIENT_INVENTORY"},
                {"Insufficient inventory (5 available, ordering 10)", "PROD-LOW-STOCK", 10, "RESERVE", "INSUFFICIENT_INVENTORY"},
                {"Invalid quantity (zero)", "PROD001", 0, "CREATE", "INVALID_QUANTITY"},
        };
    }
}
