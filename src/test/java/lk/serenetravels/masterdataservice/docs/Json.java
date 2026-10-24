package lk.serenetravels.masterdataservice.docs;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Parses and prints JSON for the API reference. Printing is done here (not by Jackson) so the layout is the same on
// every machine and every Jackson version: objects one key per line, short arrays of plain values on one line, and
// numbers exactly as the service wrote them (35.00 stays 35.00).
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final int INLINE_ARRAY_LIMIT = 100;

    private Json() {
    }

    static Object parse(String json) {
        return MAPPER.readValue(json, Object.class);
    }

    static String compact(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, -1, 0);
        return out.toString();
    }

    static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0, 0);
        return out.toString();
    }

    // indent < 0 means compact.
    @SuppressWarnings("unchecked")
    private static void write(StringBuilder out, Object value, int indent, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(out, text);
        } else if (value instanceof BigDecimal decimal) {
            out.append(decimal.toPlainString());
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, (Map<String, Object>) map, indent, depth);
        } else if (value instanceof List<?> list) {
            writeArray(out, list, indent, depth);
        } else {
            throw new IllegalArgumentException("Cannot write " + value.getClass());
        }
    }

    private static void writeObject(StringBuilder out, Map<String, Object> map, int indent, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        boolean compact = indent < 0;
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                out.append(compact ? "," : ",");
            }
            first = false;
            if (!compact) {
                out.append('\n').append("  ".repeat(depth + 1));
            }
            quote(out, entry.getKey());
            out.append(compact ? ":" : ": ");
            write(out, entry.getValue(), indent, depth + 1);
        }
        if (!compact) {
            out.append('\n').append("  ".repeat(depth));
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list, int indent, int depth) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        boolean compact = indent < 0;
        boolean plain = list.stream().noneMatch(item -> item instanceof Map || item instanceof List);
        if (compact || plain) {
            StringBuilder line = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    line.append(compact ? "," : ", ");
                }
                write(line, list.get(i), -1, 0);
            }
            line.append(']');
            if (compact || line.length() <= INLINE_ARRAY_LIMIT) {
                out.append(line);
                return;
            }
        }
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('\n').append("  ".repeat(depth + 1));
            write(out, list.get(i), indent, depth + 1);
        }
        out.append('\n').append("  ".repeat(depth)).append(']');
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // {"k": v, ...} in the order given; null values are kept (they mean "clear").
    static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
