package com.vini.soulkey.bootstrap;

import com.vini.soulkey.SoulKeySdk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Scans all property sources. If any property's value matches the pattern:
 *    soulkey.{projectId}.{envValue}
 * this post-processor will call the external API:
 *    GET http://localhost:8080/api/projects/{projectId}/env/{envValue}
 * and extract the "value" field from JSON and replace the property value in the Environment.
 */
public class SoulKeyEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PREFIX = "soulkey."; // property value should start with this
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {

        // Collect property replacements in a map: propertyName -> resolvedValue
        Map<String, Object> replacements = new HashMap<>();

        MutablePropertySources sources = environment.getPropertySources();
        for (PropertySource<?> ps : sources) {
            if (!(ps instanceof EnumerablePropertySource)) continue;
            EnumerablePropertySource<?> eps = (EnumerablePropertySource<?>) ps;
            for (String name : eps.getPropertyNames()) {
                Object rawVal = eps.getProperty(name);
                if (!(rawVal instanceof String)) continue;
                String val = (String) rawVal;

                if (val.startsWith(PREFIX)) {
                    // Remove prefix and parse projectId and envValue
                    String remainder = val.substring(PREFIX.length()); // e.g. "08a... .THRESHOLD2"
                    // We expect format: projectId.ENV
                    int dot = remainder.indexOf('.');
                    if (dot <= 0 || dot == remainder.length()-1) {
                        // invalid format, skip
                        continue;
                    }
                    String projectId = remainder.substring(0, dot);
                    String envValue = remainder.substring(dot + 1);

                    // fetch from API
                    String url = String.format("http://localhost:8080/api/projects/%s/env/%s",
                            projectId, envValue);
                    try {
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .GET()
                                .build();
                        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                            String body = resp.body();
                            JsonNode node = mapper.readTree(body);
                            if (node.has("value")) {
                                JsonNode v = node.get("value");
                                // Convert to string representation
                                String resolved = v.isTextual() ? v.asText() : v.toString();
                                replacements.put(name, resolved);
                                continue;
                            }
                        }
                    } catch (IOException | InterruptedException e) {
                        // failed to call or parse; fallback: try to use SDK internal map if present
                        try {
                            SoulKeySdk sdk = new SoulKeySdk();
                            Object sdkVal = sdk.get(projectId + "." + envValue);
                            if (sdkVal != null) replacements.put(name, sdkVal.toString());
                        } catch (Exception ex) {
                            // ignore - leave property unchanged
                        }
                    }
                }
            }
        }

        if (!replacements.isEmpty()) {
            PropertySource<?> replacement = new MapPropertySource("soulkey-resolved", replacements);
            // addFirst -> highest precedence so resolved values are seen by beans
            sources.addFirst(replacement);
        }
    }
}
