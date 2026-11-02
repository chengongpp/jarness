package ch.predmet.jarness.core.provider;

import ch.predmet.jarness.api.provider.ProviderException;
import ch.predmet.jarness.api.provider.ProviderProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Shared HTTP plumbing for the provider adapters: one {@link HttpClient} per profile, JSON POSTs,
 * authentication headers and uniform error mapping. The configured API key is never included in
 * exception messages.
 */
final class HttpTransport {

    /** Shared mapper for request/response JSON. */
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** How the profile's API key is sent on the wire. */
    enum AuthStyle {
        /** {@code Authorization: Bearer <key>}. */
        BEARER,
        /** {@code x-api-key: <key>}. */
        X_API_KEY
    }

    private static final int ERROR_BODY_SNIPPET = 500;

    private final ProviderProfile profile;
    private final AuthStyle authStyle;
    private final HttpClient client;
    private final Duration requestTimeout;

    HttpTransport(ProviderProfile profile, AuthStyle authStyle) {
        this.profile = profile;
        this.authStyle = authStyle;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.requestTimeout = Duration.ofSeconds(optionLong(profile, "timeout_seconds", 300));
    }

    static long optionLong(ProviderProfile profile, String key, long fallback) {
        Object value = profile.options().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    ProviderProfile profile() {
        return profile;
    }

    /** @return the base URL without any trailing slashes. */
    String baseUrl() {
        String base = profile.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * POST a JSON body and parse the JSON response.
     *
     * @throws ProviderException on transport failures and non-2xx statuses
     */
    JsonNode postJson(String url, ObjectNode body, Map<String, String> extraHeaders) {
        return postJson(url, body, extraHeaders, null);
    }

    JsonNode postJson(String url, ObjectNode body, Map<String, String> extraHeaders, Instant deadline) {
        HttpResponse<String> response;
        try {
            response = client.send(request(url, body, extraHeaders, deadline)
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw ProviderException.transport("request to profile \"" + profile.id() + "\" failed: "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("request to profile \"" + profile.id() + "\" interrupted", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw statusError(response.statusCode(), response.body());
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw ProviderException.parse("profile \"" + profile.id() + "\" returned non-JSON body: "
                    + snippet(response.body()), e);
        }
    }

    /**
     * POST a JSON body expecting a streamed (SSE) response; the caller owns the returned body
     * stream and must close it.
     *
     * @throws ProviderException on transport failures and non-2xx statuses
     */
    HttpResponse<InputStream> postStream(String url, ObjectNode body, Map<String, String> extraHeaders) {
        return postStream(url, body, extraHeaders, null);
    }

    HttpResponse<InputStream> postStream(String url, ObjectNode body, Map<String, String> extraHeaders,
                                         Instant deadline) {
        HttpResponse<InputStream> response;
        try {
            response = client.send(request(url, body, extraHeaders, deadline)
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw ProviderException.transport("request to profile \"" + profile.id() + "\" failed: "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("request to profile \"" + profile.id() + "\" interrupted", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody;
            try (InputStream in = response.body()) {
                errorBody = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
            } catch (IOException e) {
                errorBody = "";
            }
            throw statusError(response.statusCode(), errorBody);
        }
        return response;
    }

    private HttpRequest.Builder request(String url, ObjectNode body, Map<String, String> extraHeaders,
                                        Instant deadline) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(effectiveTimeout(deadline))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (profile.hasApiKey()) {
            switch (authStyle) {
                case BEARER -> builder.header("Authorization", "Bearer " + profile.apiKey());
                case X_API_KEY -> builder.header("x-api-key", profile.apiKey());
            }
        }
        profile.headers().forEach(builder::header);
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
        return builder;
    }

    private Duration effectiveTimeout(Instant deadline) {
        if (deadline == null) {
            return requestTimeout;
        }
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ofMillis(1);
        }
        return remaining.compareTo(requestTimeout) < 0 ? remaining : requestTimeout;
    }

    private ProviderException statusError(int status, String body) {
        return new ProviderException("profile \"" + profile.id() + "\" answered HTTP " + status + ": "
                + snippet(body), status, null);
    }

    /** First ~500 chars of an error body, with the API key redacted. */
    private String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "<empty body>";
        }
        String redacted = profile.hasApiKey()
                ? body.replace(profile.apiKey(), "***")
                : body;
        redacted = redacted.strip();
        return redacted.length() <= ERROR_BODY_SNIPPET
                ? redacted
                : redacted.substring(0, ERROR_BODY_SNIPPET) + "...";
    }

    /** @return textual content of {@code node}, or {@code null} when absent or JSON null. */
    static String textOf(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /** @return lowercase content type, or an empty string. */
    static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type")
                .map(v -> v.toLowerCase(Locale.ROOT))
                .orElse("");
    }
}
