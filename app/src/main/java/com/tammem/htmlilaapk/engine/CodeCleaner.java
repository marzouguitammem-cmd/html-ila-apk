package com.tammem.htmlilaapk.engine;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cleans code pasted from Claude / Gemini and gets an HTML page ready for a phone screen. */
public final class CodeCleaner {

    public static final int EMPTY = 0;
    public static final int HTML = 1;
    public static final int REACT = 2;
    public static final int NOT_HTML = 3;

    private static final Pattern FENCE = Pattern.compile("```([\\w+#.-]*)[^\\n]*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern META_CHARSET = Pattern.compile("<meta[^>]*charset", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_VIEWPORT =
            Pattern.compile("<meta[^>]*name\\s*=\\s*[\"']?viewport", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<html(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCTYPE = Pattern.compile("<!doctype[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_TAG = Pattern.compile("<[a-zA-Z!][^>]*>");

    private CodeCleaner() {
    }

    /**
     * If the text is a whole chat answer with ``` blocks, keeps only the HTML block
     * (or the biggest block). Otherwise returns the text trimmed.
     */
    public static String extractCode(String text) {
        if (text == null) return "";
        String t = text.replace("\r\n", "\n").replace('\u00A0', ' ').trim();
        if (!t.contains("```")) return t;
        Matcher m = FENCE.matcher(t);
        String best = null;
        long bestScore = -1;
        while (m.find()) {
            String lang = m.group(1).toLowerCase(Locale.ROOT);
            String body = m.group(2);
            String lower = body.toLowerCase(Locale.ROOT);
            long score = body.length();
            if (lang.equals("html") || lang.equals("htm") || lang.equals("xhtml")) score += 1_000_000L;
            if (lower.contains("<html") || lower.contains("<!doctype")) score += 500_000L;
            if (score > bestScore) {
                bestScore = score;
                best = body;
            }
        }
        if (best != null) return best.trim();
        // A block that was opened but never closed (answer cut off): drop the fence line.
        int fence = t.indexOf("```");
        int lineEnd = t.indexOf('\n', fence);
        String rest = lineEnd >= 0 ? t.substring(lineEnd + 1) : "";
        return rest.replace("```", "").trim();
    }

    public static int kind(String code) {
        if (code == null || code.trim().isEmpty()) return EMPTY;
        String l = code.toLowerCase(Locale.ROOT);
        boolean page = l.contains("<html") || l.contains("<!doctype");
        if (!page && (l.contains("import react") || l.contains("from 'react'") || l.contains("from \"react\"")
                || (l.contains("export default") && !l.contains("<script")))) {
            return REACT;
        }
        if (page || ANY_TAG.matcher(code).find()) return HTML;
        return NOT_HTML;
    }

    /** Adds a UTF-8 charset and a mobile viewport if the page has none. */
    public static String prepareHtml(String html) {
        boolean hasCharset = META_CHARSET.matcher(html).find();
        boolean hasViewport = META_VIEWPORT.matcher(html).find();
        if (hasCharset && hasViewport) return html;
        String inject = (hasCharset ? "" : "<meta charset=\"utf-8\">")
                + (hasViewport ? "" : "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        Matcher head = HEAD.matcher(html);
        if (head.find()) return insert(html, head.end(), inject);
        Matcher tag = HTML_TAG.matcher(html);
        if (tag.find()) return insert(html, tag.end(), "<head>" + inject + "</head>");
        Matcher doctype = DOCTYPE.matcher(html);
        if (doctype.find()) return insert(html, doctype.end(), inject);
        return inject + "\n" + html;
    }

    private static String insert(String s, int at, String what) {
        return s.substring(0, at) + what + s.substring(at);
    }
}
