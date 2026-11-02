package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code web_search} — search the web for current information via DuckDuckGo Lite. Up to 4 queries,
 * up to 8 results per query. Network or parse failures become {@link ToolOutcome#error}, never
 * exceptions.
 */
public final class WebSearchTool implements Tool {

    private static final int MAX_QUERIES = 4;
    private static final int MAX_RESULTS_PER_QUERY = 8;
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final Pattern ANCHOR =
            Pattern.compile("<a\\s([^>]*)>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HREF =
            Pattern.compile("href\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SNIPPET = Pattern.compile(
            "<td[^>]*class\\s*=\\s*['\"]result-snippet['\"][^>]*>(.*?)</td>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ToolSpec spec = ToolSpec.readOnly(
            "web_search",
            "Search the web for current information.",
            params());

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("queries", ToolParameter.required("array", "1-4 search queries."));
        return params;
    }

    /** Create the tool. */
    public WebSearchTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            List<String> queries = invocation.stringList("queries");
            if (queries.isEmpty()) {
                throw new ToolException("missing required parameter \"queries\"");
            }
            if (queries.size() > MAX_QUERIES) {
                queries = queries.subList(0, MAX_QUERIES);
            }
            StringBuilder out = new StringBuilder();
            for (String query : queries) {
                if (query.isBlank()) {
                    continue;
                }
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append("== ").append(query).append(" ==\n");
                List<Result> results = search(query, ctx);
                if (results.isEmpty()) {
                    out.append("(no results)\n");
                    continue;
                }
                int i = 1;
                for (Result result : results) {
                    out.append(i++).append(". ").append(result.title()).append(" — ").append(result.url());
                    if (!result.snippet().isEmpty()) {
                        out.append("\n   ").append(result.snippet());
                    }
                    out.append('\n');
                }
            }
            String text = out.toString().trim();
            if (text.isEmpty()) {
                return ToolOutcome.error("web_search produced no output");
            }
            return ToolOutcome.ok(text);
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("web_search failed: " + e.getMessage());
        }
    }

    private List<Result> search(String query, ToolContext ctx) throws Exception {
        String url = "https://lite.duckduckgo.com/lite/?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        ctx.requireResource(CapKind.NET, "lite.duckduckgo.com");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new java.io.IOException("search backend returned HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    /** Parse a DuckDuckGo Lite results page: result-link anchors paired with result snippets. */
    private static List<Result> parse(String html) {
        List<String> titles = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        Matcher anchors = ANCHOR.matcher(html);
        while (anchors.find() && urls.size() < MAX_RESULTS_PER_QUERY) {
            String attrs = anchors.group(1);
            if (!attrs.contains("result-link")) {
                continue;
            }
            Matcher hrefMatcher = HREF.matcher(attrs);
            if (!hrefMatcher.find()) {
                continue;
            }
            String resolved = resolveUrl(Html.decodeEntities(hrefMatcher.group(1)));
            if (resolved == null) {
                continue;
            }
            titles.add(Html.fragment(anchors.group(2)));
            urls.add(resolved);
        }
        if (urls.isEmpty()) {
            // fallback: any anchor pointing off-site, inside result rows or not
            Matcher fallback = ANCHOR.matcher(html);
            while (fallback.find() && urls.size() < MAX_RESULTS_PER_QUERY) {
                Matcher hrefMatcher = HREF.matcher(fallback.group(1));
                if (!hrefMatcher.find()) {
                    continue;
                }
                String resolved = resolveUrl(Html.decodeEntities(hrefMatcher.group(1)));
                if (resolved == null) {
                    continue;
                }
                String title = Html.fragment(fallback.group(2));
                if (title.isEmpty()) {
                    continue;
                }
                titles.add(title);
                urls.add(resolved);
            }
        }
        List<String> snippets = new ArrayList<>();
        Matcher snippetMatcher = SNIPPET.matcher(html);
        while (snippetMatcher.find() && snippets.size() < urls.size()) {
            snippets.add(Html.fragment(snippetMatcher.group(1)));
        }
        List<Result> results = new ArrayList<>(urls.size());
        for (int i = 0; i < urls.size(); i++) {
            results.add(new Result(titles.get(i), urls.get(i),
                    i < snippets.size() ? snippets.get(i) : ""));
        }
        return results;
    }

    /** Unwrap DuckDuckGo redirect URLs and drop internal links; {@code null} means "skip". */
    private static String resolveUrl(String href) {
        if (href == null || href.isBlank() || href.startsWith("#") || href.startsWith("javascript:")) {
            return null;
        }
        String url = href;
        int uddg = url.indexOf("uddg=");
        if (uddg >= 0) {
            String encoded = url.substring(uddg + 5);
            int amp = encoded.indexOf('&');
            if (amp >= 0) {
                encoded = encoded.substring(0, amp);
            }
            url = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } else if (url.startsWith("//")) {
            url = "https:" + url;
        }
        if (!url.startsWith("http")) {
            return null;
        }
        if (url.contains("duckduckgo.com")) {
            return null;
        }
        return url;
    }

    private record Result(String title, String url, String snippet) {
    }
}
