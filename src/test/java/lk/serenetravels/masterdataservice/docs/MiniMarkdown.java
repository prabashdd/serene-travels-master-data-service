package lk.serenetravels.masterdataservice.docs;

import java.util.ArrayList;
import java.util.List;

// Just enough Markdown for docs/api-reference/intro.md: headings, paragraphs, bullet lists, pipe tables, fenced code,
// `code`, **bold** and [links](#anchor). It is deliberately small; if the intro starts using something else, the
// output will show it plainly and this is the place to teach it.
final class MiniMarkdown {

    private MiniMarkdown() {
    }

    static String toHtml(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.startsWith("```")) {
                String language = line.substring(3).trim();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                i++;
                String text = code.toString().stripTrailing();
                out.append("<div class=\"code\"><button type=\"button\" class=\"copy\">Copy</button><pre><code>")
                        .append(language.equals("json") ? ApiReferenceHtml.highlightJson(text) : ApiReferenceHtml.escape(text))
                        .append("</code></pre></div>\n");
            } else if (line.matches("#{1,4} .*")) {
                int level = line.indexOf(' ');
                String text = line.substring(level + 1).trim();
                out.append("<h").append(level).append(" id=\"").append(ApiReferenceRenderer.slug(text)).append("\">")
                        .append(inline(text)).append("</h").append(level).append(">\n");
                i++;
            } else if (line.startsWith("|")) {
                List<String> rows = new ArrayList<>();
                while (i < lines.length && lines[i].startsWith("|")) {
                    rows.add(lines[i]);
                    i++;
                }
                out.append(table(rows));
            } else if (line.startsWith("- ")) {
                out.append("<ul>\n");
                while (i < lines.length && lines[i].startsWith("- ")) {
                    out.append("<li>").append(inline(lines[i].substring(2).trim())).append("</li>\n");
                    i++;
                }
                out.append("</ul>\n");
            } else if (line.isBlank()) {
                i++;
            } else {
                StringBuilder paragraph = new StringBuilder();
                while (i < lines.length && !lines[i].isBlank() && !lines[i].startsWith("```") && !lines[i].startsWith("|")
                        && !lines[i].startsWith("- ") && !lines[i].matches("#{1,4} .*")) {
                    paragraph.append(paragraph.isEmpty() ? "" : " ").append(lines[i].trim());
                    i++;
                }
                out.append("<p>").append(inline(paragraph.toString())).append("</p>\n");
            }
        }
        return out.toString();
    }

    private static String table(List<String> rows) {
        StringBuilder out = new StringBuilder("<div class=\"table-wrap\"><table>\n<thead><tr>");
        for (String cell : cells(rows.get(0))) {
            out.append("<th>").append(inline(cell)).append("</th>");
        }
        out.append("</tr></thead>\n<tbody>\n");
        for (int r = 2; r < rows.size(); r++) {     // row 1 is the |---|---| separator
            out.append("<tr>");
            for (String cell : cells(rows.get(r))) {
                out.append("<td>").append(inline(cell)).append("</td>");
            }
            out.append("</tr>\n");
        }
        return out.append("</tbody></table></div>\n").toString();
    }

    private static List<String> cells(String row) {
        String trimmed = row.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("(?<!\\\\)\\|", -1)) {
            cells.add(cell.strip().replace("\\|", "|"));
        }
        return cells;
    }

    // `code` first (its content is literal), then **bold** and [links](url) in the text around it.
    static String inline(String text) {
        String[] parts = text.split("`", -1);
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < parts.length; k++) {
            if (k % 2 == 1) {
                out.append("<code>").append(ApiReferenceHtml.escape(parts[k])).append("</code>");
            } else {
                out.append(ApiReferenceHtml.escape(parts[k])
                        .replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
                        .replaceAll("\\[([^\\]]+)]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>"));
            }
        }
        return out.toString();
    }
}
