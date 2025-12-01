package com.vini.soulkey.bootstrap;

import com.vini.soulkey.SoulKeySdk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * EnvironmentPostProcessor that resolves property values that reference soulkey keys.
 *
 * Example:
 *   application.properties:
 *     threshold = soulkey.08a10664-aadd-440f-9836-612dc8ac397a.THRESHOLD2
 *
 * If value startsWith "soulkey.", this processor will call:
 *   GET http://localhost:8080/api/projects/{projectId}/env/{envValue}
 *
 * And use the JSON "value" and "type" to create a typed property value in the Environment.
 */
public class SoulKeyEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PREFIX = "soulkey.";
    private static final String PROPERTY_SOURCE_NAME = "soulkey-resolved";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {

        MutablePropertySources sources = environment.getPropertySources();
        Map<String, Object> replacements = new HashMap<>();

        // Create SDK instance for fallback (contains internal typed defaults)
        SoulKeySdk sdk = new SoulKeySdk();

        for (PropertySource<?> ps : sources) {
            if (!(ps instanceof EnumerablePropertySource)) continue;
            EnumerablePropertySource<?> eps = (EnumerablePropertySource<?>) ps;
            for (String name : eps.getPropertyNames()) {
                Object rawVal = eps.getProperty(name);
                if (!(rawVal instanceof String)) continue;
                String val = (String) rawVal;

                if (!val.startsWith(PREFIX)) continue;

                // parse key: expected format soulkey.{projectId}.{envValue}
                String remainder = val.substring(PREFIX.length());
                int dot = remainder.indexOf('.');
                if (dot <= 0 || dot == remainder.length() - 1) {
                    // invalid format; skip replacement for this property
                    continue;
                }
                String projectId = remainder.substring(0, dot);
                String envValue = remainder.substring(dot + 1);

                // Try remote API first
                Object resolved = null;
                try {
                    String url = String.format("http://localhost:8080/api/projects/%s/env/%s",
                            projectId, envValue);
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .GET()
                            .build();

                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        String body = resp.body();
                        JsonNode root = mapper.readTree(body);

                        // Expect JSON: { "key": "...", "value": <...>, "type": "integer|string|decimal|boolean" }
                        JsonNode typeNode = root.get("type");
                        JsonNode valueNode = root.get("value");

                        if (typeNode != null && valueNode != null && !valueNode.isNull()) {
                            String type = typeNode.asText().toLowerCase(Locale.ROOT);
                            resolved = convertNodeToTypedValue(valueNode, type);
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    // remote call failed or interrupted — we'll fallback to SDK defaults below
                } catch (Exception e) {
                    // JSON parse or conversion issue — fallback below
                }

                // Fallback: if remote didn't yield a resolved value, check SDK internal map
                if (resolved == null) {
                    // SDK keys stored as "projectId.envValue" inside sdk.get(...)
                    Object sdkVal = sdk.get(projectId + "." + envValue);
                    if (sdkVal != null) {
                        resolved = sdkVal;
                    }
                }

                if (resolved != null) {
                    replacements.put(name, resolved);
                }
            }
        }

        if (!replacements.isEmpty()) {
            // Put resolved typed values into a property source with high precedence
            PropertySource<?> ps = new MapPropertySource(PROPERTY_SOURCE_NAME, replacements);
            // addFirst so beans see resolved values immediately
            sources.addFirst(ps);
        }
    }

    /**
     * Convert a Jackson JsonNode to a Java typed object according to 'type'.
     */
    private Object convertNodeToTypedValue(JsonNode valueNode, String type) {
        switch (type) {
            case "string":
                // Return as plain Java String (unquoted), common for DB URLs / JDBC strings
                return valueNode.isTextual() ? valueNode.asText() : valueNode.toString();

            case "integer":
                // Use Long to cover large integers
                if (valueNode.canConvertToLong()) {
                    return valueNode.asLong();
                } else {
                    // fallback to BigDecimal then longValue
                    try {
                        BigDecimal bd = new BigDecimal(valueNode.asText());
                        return bd.longValue();
                    } catch (Exception e) {
                        return valueNode.asText();
                    }
                }

            case "decimal":
                // Use BigDecimal for precise decimal numbers
                try {
                    if (valueNode.isNumber()) {
                        return valueNode.decimalValue();
                    } else {
                        return new BigDecimal(valueNode.asText());
                    }
                } catch (Exception e) {
                    // fallback to string if parse fails
                    return valueNode.asText();
                }

            case "boolean":
                if (valueNode.isBoolean()) {
                    return valueNode.asBoolean();
                } else {
                    String s = valueNode.asText();
                    return Boolean.parseBoolean(s);
                }

            default:
                // unknown type — return textual representation
                return valueNode.isTextual() ? valueNode.asText() : valueNode.toString();
        }
    }
}
