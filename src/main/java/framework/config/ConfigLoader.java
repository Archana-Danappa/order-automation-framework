package framework.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the environment-specific YAML file selected by the `env` system
 * property (default "dev") and caches it as a singleton for the run.
 *
 * Usage:
 *   mvn clean test -Denv=qa
 *
 * Secrets handling (Section 18 - never commit real credentials):
 * any YAML value written as ${ENV_VAR_NAME} is resolved from an actual
 * environment variable at load time. Checked-in YAML therefore only ever
 * contains placeholders, never real secrets. For this assessment's mock
 * backend there are no real credentials in play, but the mechanism is here
 * so the framework doesn't need to change shape to point at real
 * MySQL/Mongo/Redis instances later.
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("^\\$\\{(.+)}$");
    private static volatile EnvironmentConfig cachedConfig;

    private ConfigLoader() {
    }

    public static synchronized EnvironmentConfig get() {
        if (cachedConfig == null) {
            String env = System.getProperty("env", "dev");
            cachedConfig = load(env);
            log.info("Loaded configuration for environment '{}'", env);
        }
        return cachedConfig;
    }

    private static EnvironmentConfig load(String env) {
        String resource = "config/" + env + ".yaml";
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("Configuration file not found on classpath: " + resource);
            }
            Yaml yaml = new Yaml(new Constructor(EnvironmentConfig.class, new org.yaml.snakeyaml.LoaderOptions()));
            EnvironmentConfig config = yaml.load(is);
            resolveEnvVarPlaceholders(config);
            return config;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load configuration for env=" + env, e);
        }
    }

    /**
     * Walks the config object's String fields (including nested config
     * objects) and replaces any "${VAR_NAME}" value with the matching
     * environment variable, if set.
     */
    private static void resolveEnvVarPlaceholders(Object target) {
        if (target == null) {
            return;
        }
        for (Field field : target.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            try {
                Object value = field.get(target);
                if (value instanceof String str) {
                    Matcher matcher = ENV_VAR_PATTERN.matcher(str);
                    if (matcher.matches()) {
                        String envVarName = matcher.group(1);
                        String resolved = System.getenv(envVarName);
                        if (resolved != null) {
                            field.set(target, resolved);
                        } else {
                            log.warn("Environment variable {} not set; leaving placeholder unresolved", envVarName);
                        }
                    }
                } else if (value != null && value.getClass().getName().startsWith("framework.config")) {
                    resolveEnvVarPlaceholders(value);
                }
            } catch (IllegalAccessException e) {
                log.warn("Unable to read field {} while resolving config placeholders", field.getName());
            }
        }
    }

    /** Test-only escape hatch, e.g. for unit-testing the loader itself. */
    static void reset() {
        cachedConfig = null;
    }
}
