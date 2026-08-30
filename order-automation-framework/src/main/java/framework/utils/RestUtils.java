package framework.utils;

import framework.config.ConfigLoader;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Single choke point for every HTTP call the framework makes. No helper
 * ever calls RestAssured.given() directly - they all go through here, so
 * request/response logging, base-URI resolution, and header handling are
 * implemented exactly once.
 */
public final class RestUtils {

    private static final Logger log = LoggerFactory.getLogger(RestUtils.class);

    private RestUtils() {
    }

    private static RequestSpecification baseSpec(Map<String, String> headers, Map<String, ?> queryParams) {
        RestAssured.baseURI = ConfigLoader.get().getBaseUrl();
        RequestSpecification spec = RestAssured.given()
                .contentType("application/json");
        if (headers != null && !headers.isEmpty()) {
            spec = spec.headers(headers);
        }
        if (queryParams != null && !queryParams.isEmpty()) {
            spec = spec.queryParams(queryParams);
        }
        return spec;
    }

    public static Response post(String path, Map<String, String> headers, String body) {
        log.info("POST {} | headers={} | body={}", path, safeHeaders(headers), body);
        Response response = baseSpec(headers, null)
                .body(body)
                .when()
                .post(path)
                .then()
                .extract()
                .response();
        log.info("Response {} {} <- {}", response.statusCode(), path, response.asPrettyString());
        return response;
    }

    public static Response get(String path, Map<String, String> headers) {
        return get(path, headers, null);
    }

    public static Response get(String path, Map<String, String> headers, Map<String, ?> queryParams) {
        log.info("GET {} | headers={}", path, safeHeaders(headers));
        Response response = baseSpec(headers, queryParams)
                .when()
                .get(path)
                .then()
                .extract()
                .response();
        log.info("Response {} {} <- {}", response.statusCode(), path, response.asPrettyString());
        return response;
    }

    public static Response put(String path, Map<String, String> headers, String body) {
        log.info("PUT {} | headers={} | body={}", path, safeHeaders(headers), body);
        Response response = baseSpec(headers, null)
                .body(body)
                .when()
                .put(path)
                .then()
                .extract()
                .response();
        log.info("Response {} {} <- {}", response.statusCode(), path, response.asPrettyString());
        return response;
    }

    public static Response delete(String path, Map<String, String> headers) {
        log.info("DELETE {} | headers={}", path, safeHeaders(headers));
        Response response = baseSpec(headers, null)
                .when()
                .delete(path)
                .then()
                .extract()
                .response();
        log.info("Response {} {} <- {}", response.statusCode(), path, response.asPrettyString());
        return response;
    }

    /**
     * Masks anything that looks like a credential/token/card field before
     * it ever reaches a log line (Section 21 - never log secrets).
     */
    private static Map<String, String> safeHeaders(Map<String, String> headers) {
        if (headers == null) {
            return Map.of();
        }
        Map<String, String> masked = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey().toLowerCase();
            boolean sensitive = key.contains("authorization") || key.contains("token")
                    || key.contains("password") || key.contains("card");
            masked.put(entry.getKey(), sensitive ? "****" : entry.getValue());
        }
        return masked;
    }
}
