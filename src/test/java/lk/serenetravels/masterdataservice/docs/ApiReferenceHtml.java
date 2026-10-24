package lk.serenetravels.masterdataservice.docs;

import lk.serenetravels.masterdataservice.docs.ApiReferenceRenderer.Area;
import lk.serenetravels.masterdataservice.docs.ApiReferenceRenderer.Op;
import lk.serenetravels.masterdataservice.docs.ApiReferenceRenderer.Param;
import lk.serenetravels.masterdataservice.docs.ApiReferenceRenderer.Sample;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The same reference as the Markdown file, as one self-contained page: a rail of areas and endpoints, search, collapsible
// field lists, highlighted samples with copy buttons, light and dark themes. It reads the model the Markdown renderer
// builds, so the two cannot disagree. No external files, fonts or scripts: it opens from disk and works offline.
final class ApiReferenceHtml {

    static final String TITLE = "Master Data API";
    private static final Pattern JSON_TOKEN = Pattern.compile(
            "\"(?:[^\"\\\\]|\\\\.)*\"(\\s*:)?|-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b|\\b(?:true|false|null)\\b");

    private ApiReferenceHtml() {
    }

    /** A complete, standalone document. */
    static String document(ApiReferenceRenderer renderer, String css, String js) {
        return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
                + "<meta name=\"claude:viewport-fit-sides\" content=\"cover\">\n"
                + "<title>" + TITLE + "</title>\n<style>\n" + css.strip() + "\n</style>\n</head>\n<body>\n"
                + body(renderer) + "<script>\n" + js.strip() + "\n</script>\n</body>\n</html>\n";
    }

    private static String body(ApiReferenceRenderer renderer) {
        List<Area> areas = renderer.areas();
        List<Sample> commonSamples = renderer.commonSamples();
        int endpoints = areas.stream().mapToInt(a -> a.ops().size()).sum();
        int samples = areas.stream().flatMap(a -> a.ops().stream()).mapToInt(o -> o.samples().size()).sum()
                + commonSamples.size();

        StringBuilder html = new StringBuilder();
        html.append("<header class=\"top\"><div class=\"top-inner\">\n")
                .append("<button type=\"button\" class=\"ghost menu\" id=\"menu\" aria-expanded=\"false\" "
                        + "aria-controls=\"rail\">Menu</button>\n")
                .append("<div class=\"brand\">Master Data API <span>reference</span></div>\n")
                .append("<div class=\"search\"><input id=\"q\" type=\"search\" placeholder=\"Search endpoints  ( / )\" "
                        + "aria-label=\"Search endpoints\" autocomplete=\"off\"></div>\n")
                .append("<button type=\"button\" class=\"ghost theme\" id=\"theme\">Theme: auto</button>\n")
                .append("</div></header>\n");

        html.append("<div class=\"shell\">\n");
        rail(html, areas);

        html.append("<main>\n");
        html.append("<h1>Master Data Service API</h1>\n");
        html.append("<p class=\"lede\">Reference data and prices for the travel business: every endpoint, what it accepts, "
                + "who may call it, and a real request and response.</p>\n");
        html.append("<ul class=\"facts\"><li><b>").append(endpoints).append("</b> endpoints</li><li><b>")
                .append(areas.size()).append("</b> areas</li><li><b>").append(samples)
                .append("</b> real examples</li><li id=\"found\" hidden></li></ul>\n");

        html.append("<section class=\"prose\" id=\"about\">\n").append(MiniMarkdown.toHtml(renderer.intro())).append("</section>\n");

        html.append("<section class=\"prose area-common\" id=\"common-error-responses\">\n<h2>Common error responses</h2>\n");
        html.append("<p>Every error is <code>application/problem+json</code> with a stable <code>code</code>. Real examples:</p>\n");
        for (Sample sample : commonSamples) {
            sample(html, sample, null);
        }
        html.append("</section>\n");

        html.append("<p class=\"empty\" id=\"empty\" hidden>No endpoint matches. Try a path, a field name or a word from "
                + "its description.</p>\n");
        for (Area area : areas) {
            area(html, area);
        }

        html.append("<p class=\"foot\">Generated from the controllers and a scripted run of the real API by "
                + "<code>ApiReferenceTest</code>; the test fails if this page and the code disagree. "
                + "To update it, see <code>docs/api-reference/README.md</code>.</p>\n");
        html.append("</main>\n</div>\n");
        return html.toString();
    }

    private static void rail(StringBuilder html, List<Area> areas) {
        html.append("<nav class=\"rail\" id=\"rail\" aria-label=\"Areas and endpoints\">\n");
        html.append("<div class=\"top-links\"><a href=\"#about\">About this API</a>"
                + "<a href=\"#common-error-responses\">Common errors</a></div>\n");
        for (Area area : areas) {
            html.append("<details class=\"area\" open><summary>").append(escape(area.name()))
                    .append("<span class=\"count\">").append(area.ops().size()).append("</span></summary><div>\n");
            for (Op op : area.ops()) {
                html.append("<a href=\"#").append(op.anchor()).append("\" data-op=\"").append(op.anchor()).append("\">")
                        .append(badge(op.method())).append("<span class=\"p\">").append(escape(op.shortPath()))
                        .append("</span></a>\n");
            }
            html.append("</div></details>\n");
        }
        html.append("</nav>\n");
    }

    private static void area(StringBuilder html, Area area) {
        html.append("<section class=\"area-block\" id=\"").append(ApiReferenceRenderer.slug(area.name())).append("\">\n");
        html.append("<h2>").append(escape(area.name())).append("</h2>\n");
        if (!area.description().isBlank()) {
            html.append("<p>").append(escape(area.description())).append("</p>\n");
        }
        for (Op op : area.ops()) {
            op(html, op);
        }
        html.append("</section>\n");
    }

    private static void op(StringBuilder html, Op op) {
        html.append("<section class=\"op\" id=\"").append(op.anchor()).append("\" data-search=\"")
                .append(escape(searchText(op))).append("\">\n");
        html.append("<div class=\"op-head\">").append(badge(op.method())).append("<span class=\"path\">")
                .append(escape(op.shortPath())).append("</span></div>\n");
        html.append("<h3>").append(escape(op.summary())).append("</h3>\n");
        if (!op.description().isBlank()) {
            html.append("<p>").append(escape(op.description())).append("</p>\n");
        }
        html.append("<p class=\"who\">Who can call it: <b>").append(escape(op.roles())).append("</b></p>\n");

        if (!op.params().isEmpty()) {
            html.append("<div class=\"sub\">Parameters</div>\n<div class=\"table-wrap\"><table><thead><tr>"
                    + "<th>Name</th><th>In</th><th>Type</th><th>Required</th><th>Default</th><th>Notes</th></tr></thead><tbody>\n");
            for (Param param : op.params()) {
                html.append("<tr><td><code>").append(escape(param.name())).append("</code></td><td>").append(param.in())
                        .append("</td><td>").append(escape(param.type())).append("</td><td>")
                        .append(param.required() ? "yes" : "no").append("</td><td>")
                        .append(param.defaultValue() == null ? "" : "<code>" + escape(param.defaultValue()) + "</code>")
                        .append("</td><td>").append(escape(param.note())).append("</td></tr>\n");
            }
            html.append("</tbody></table></div>\n");
        }
        if (op.bodyDescription() != null) {
            String body = op.bodyDescription();
            html.append("<div class=\"sub\">Request body</div>\n<p>")
                    .append(escape(Character.toUpperCase(body.charAt(0)) + body.substring(1))).append("</p>\n");
            if (!op.bodyRows().isEmpty()) {
                fieldTable(html, op.bodyRows(), true);
            }
        }
        if (!op.responseRows().isEmpty()) {
            html.append("<details class=\"fields\"><summary>Response fields</summary>\n");
            fieldTable(html, op.responseRows(), false);
            html.append("</details>\n");
        }
        for (Sample sample : op.samples()) {
            sample(html, sample, "Example");
        }
        html.append("</section>\n");
    }

    private static void fieldTable(StringBuilder html, List<String[]> rows, boolean withRequired) {
        html.append("<div class=\"table-wrap\"><table><thead><tr><th>Field</th><th>Type</th>")
                .append(withRequired ? "<th>Required</th>" : "").append("</tr></thead><tbody>\n");
        for (String[] row : rows) {
            html.append("<tr><td><code>").append(escape(row[0])).append("</code></td><td>").append(escape(row[1])).append("</td>")
                    .append(withRequired ? "<td>" + row[2] + "</td>" : "").append("</tr>\n");
        }
        html.append("</tbody></table></div>\n");
    }

    private static void sample(StringBuilder html, Sample sample, String label) {
        html.append("<figure class=\"sample\" style=\"margin-inline:0\">\n<header>");
        if (label != null) {
            html.append("<span class=\"tag\">").append(label).append(" -</span>");
        }
        html.append("<span class=\"title\">").append(escape(sample.title())).append("</span></header>\n");

        html.append("<div class=\"cap\">Request</div>\n<div class=\"code\"><button type=\"button\" class=\"copy\">Copy</button><pre><code>");
        html.append("<span class=\"v ").append(methodClass(sample.method())).append("\" style=\"color:var(--c)\">")
                .append(sample.method()).append("</span> ").append(escape(sample.requestLine().substring(sample.method().length() + 1)));
        sample.requestHeaders().forEach((name, value) -> html.append('\n').append("<span class=\"hn\">")
                .append(escape(name)).append(":</span> ").append(escape(value)));
        if (sample.requestBody() != null) {
            html.append("\n<span class=\"hn\">Content-Type:</span> application/json\n\n");
            html.append(sample.requestBody().stripLeading().startsWith("{") || sample.requestBody().stripLeading().startsWith("[")
                    ? highlightJson(sample.requestBody()) : escape(sample.requestBody()));
        }
        html.append("</code></pre></div>\n");

        html.append("<div class=\"cap\"><span>Response</span><span class=\"status s").append(sample.status() / 100).append("\">")
                .append(sample.status()).append(' ').append(sample.statusName()).append("</span>");
        if (sample.location() != null) {
            html.append("<span class=\"extra\">Location: ").append(escape(sample.location())).append("</span>");
        }
        if (sample.etag() != null) {
            html.append("<span class=\"extra\">ETag: ").append(escape(sample.etag())).append("</span>");
        }
        html.append("</div>\n");
        if (sample.responseBody() != null) {
            html.append("<div class=\"code\"><button type=\"button\" class=\"copy\">Copy</button><pre><code>")
                    .append(highlightJson(sample.responseBody())).append("</code></pre></div>\n");
        }
        html.append("</figure>\n");
    }

    private static String badge(String method) {
        return "<span class=\"m " + methodClass(method) + "\">" + method + "</span>";
    }

    private static String methodClass(String method) {
        return "m-" + method.toLowerCase(Locale.ROOT);
    }

    // Everything a reader might search for, lower-cased: path, summary, description, who, parameters, example titles.
    private static String searchText(Op op) {
        StringBuilder text = new StringBuilder();
        text.append(op.method()).append(' ').append(op.shortPath()).append(' ').append(op.summary()).append(' ')
                .append(op.description()).append(' ').append(op.roles());
        op.params().forEach(p -> text.append(' ').append(p.name()).append(' ').append(p.note()));
        op.samples().forEach(s -> text.append(' ').append(s.title()));
        return text.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // JSON text -> HTML with keys, strings, numbers and literals wrapped in spans. Quotes are left alone inside the text
    // (escape() would turn them into &quot; and break the pattern), so only & < > are escaped first.
    static String highlightJson(String json) {
        String text = json.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        Matcher matcher = JSON_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String css;
            if (token.startsWith("\"")) {
                css = matcher.group(1) != null ? "k" : "s";
            } else if (token.equals("true") || token.equals("false") || token.equals("null")) {
                css = "l";
            } else {
                css = "n";
            }
            String replacement;
            if (css.equals("k")) {
                String key = token.substring(0, token.length() - matcher.group(1).length());
                replacement = "<span class=\"k\">" + key + "</span>" + matcher.group(1);
            } else {
                replacement = "<span class=\"" + css + "\">" + token + "</span>";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
