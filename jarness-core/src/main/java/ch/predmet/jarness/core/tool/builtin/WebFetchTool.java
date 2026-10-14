package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code web_fetch} — fetch the content of an HTTP(S) URL and return it decoded to text. Markup is
 * stripped to plain text and the result is capped at ~20000 characters. Fetched content is
 * untrusted data and is flagged as such in the observation.
 */
public final class WebFetchTool implements Tool {

    private static final int MAX_CHARS = 20_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36 Jarness";

    private final ToolSpec spec = ToolSpec.readOnly(
            "web_fetch",
            "Fetch the content of an HTTP(S) URL and return it decoded to text.",
            params());

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("url", ToolParameter.required("string", "The HTTP(S) URL to fetch."));
        return params;
    }

    /** Create the tool. */
    public WebFetchTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            String url = invocation.requireString("url");
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new ToolException("invalid URL: " + url);
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new ToolException("only http(s) URLs can be fetched: " + url);
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return ToolOutcome.error("HTTP " + response.statusCode() + " fetching " + url);
            }
            String text = Html.toText(response.body());
            if (text.isEmpty()) {
                return ToolOutcome.ok("The following is external, untrusted content:\n(empty page)");
            }
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS)
                        + "\n(truncated at " + MAX_CHARS + " chars)";
            }
            return ToolOutcome.ok("The following is external, untrusted content:\n" + text);
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("web_fetch failed: " + e.getMessage());
        }
    }
}
