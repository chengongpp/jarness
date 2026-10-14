package ch.predmet.jarness.core.tool.builtin;

import java.util.regex.Pattern;

/**
 * Small HTML-to-text helpers shared by the web tools. Package-private; not part of any contract.
 */
final class Html {

    private static final Pattern SCRIPT_STYLE =
            Pattern.compile("<(script|style)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private Html() {
    }

    /** Strip script/style blocks, then every tag, decode common entities, collapse whitespace. */
    static String toText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String text = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        text = TAG.matcher(text).replaceAll("\n");
        text = decodeEntities(text);
        text = text.replace('\u00A0', ' ');
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.trim();
    }

    /** Remove tags from a fragment (e.g. a link title) and decode entities. */
    static String fragment(String html) {
        if (html == null) {
            return "";
        }
        return decodeEntities(TAG.matcher(html).replaceAll("")).replace('\u00A0', ' ').trim();
    }

    /** Decode the handful of entities that survive in practice. */
    static String decodeEntities(String text) {
        return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }
}
