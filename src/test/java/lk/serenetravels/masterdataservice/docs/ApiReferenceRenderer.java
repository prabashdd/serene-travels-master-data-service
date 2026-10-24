package lk.serenetravels.masterdataservice.docs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Turns three things into the Markdown reference: the OpenAPI description springdoc builds from the controllers
// (endpoints, parameters, descriptions, field lists), the sample requests/responses captured from a real run, and the
// roles read from @PreAuthorize. Nothing in the output is typed by hand except the intro file.
final class ApiReferenceRenderer {

    // The areas, in reading order. A controller tag not listed here is still rendered, after these.
    private static final List<String> AREA_ORDER = List.of("Lookups", "Rates", "Properties", "Attractions", "Vehicles",
            "Drivers", "Guides", "Restaurants", "Packages", "Promotions", "Distances & Articles", "Operations");
    private static final List<String> METHOD_ORDER = List.of("get", "post", "put", "patch", "delete");
    private static final Map<Integer, String> STATUS_NAMES = Map.of(200, "OK", 201, "Created", 204, "No Content",
            304, "Not Modified", 400, "Bad Request", 401, "Unauthorized", 403, "Forbidden", 404, "Not Found",
            409, "Conflict");
    private static final int MAX_DEPTH = 4;

    /**
     * One captured request/response pair. Bodies are already pretty-printed JSON (or null). {@code template} is the
     * path with {placeholders}, matching the OpenAPI path; {@code path} is the concrete path that was called.
     */
    record Sample(String title, String method, String template, String path, List<String[]> query,
                  Map<String, String> requestHeaders, String requestBody, int status, String location, String etag,
                  String responseBody) {
        String key() {
            return method + " " + template;
        }
    }

    private final Map<String, Object> apiDocs;
    private final List<Sample> operationSamples;
    private final List<Sample> commonSamples;
    private final Map<String, String> roles;
    private final String intro;

    ApiReferenceRenderer(String intro, Map<String, Object> apiDocs, List<Sample> operationSamples,
                         List<Sample> commonSamples, Map<String, String> roles) {
        this.intro = intro;
        this.apiDocs = apiDocs;
        this.operationSamples = operationSamples;
        this.commonSamples = commonSamples;
        this.roles = roles;
    }

    // ------------------------------------------------------------------ operations from the OpenAPI document

    private record Operation(String method, String path, Map<String, Object> spec) {
        String key() {
            return method.toUpperCase() + " " + path;
        }

        String tag() {
            List<?> tags = (List<?>) spec.get("tags");
            return tags == null || tags.isEmpty() ? "Other" : (String) tags.get(0);
        }

        String summary() {
            return (String) spec.getOrDefault("summary", "");
        }
    }

    @SuppressWarnings("unchecked")
    List<String> operationKeys() {
        return operations().stream().map(Operation::key).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Operation> operations() {
        List<Operation> result = new ArrayList<>();
        Map<String, Object> paths = (Map<String, Object>) apiDocs.get("paths");
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            if (!path.getKey().startsWith("/api/v1/master-data")) {
                continue;
            }
            for (Map.Entry<String, Object> method : ((Map<String, Object>) path.getValue()).entrySet()) {
                if (METHOD_ORDER.contains(method.getKey())) {
                    result.add(new Operation(method.getKey(), path.getKey(), (Map<String, Object>) method.getValue()));
                }
            }
        }
        result.sort(Comparator
                .comparingInt((Operation o) -> areaRank(o.tag()))
                .thenComparing(Operation::path)
                .thenComparingInt(o -> METHOD_ORDER.indexOf(o.method())));
        return result;
    }

    private static int areaRank(String tag) {
        int index = AREA_ORDER.indexOf(tag);
        return index < 0 ? AREA_ORDER.size() : index;
    }

    // ------------------------------------------------------------------ rendering

    String render() {
        List<Operation> operations = operations();
        Map<String, List<Operation>> byArea = new LinkedHashMap<>();
        for (Operation operation : operations) {
            byArea.computeIfAbsent(operation.tag(), k -> new ArrayList<>()).add(operation);
        }

        StringBuilder md = new StringBuilder();
        md.append("# Master Data Service - API reference\n\n");
        md.append("> **This file is generated.** Do not edit it by hand: the next test run would fail. It is built from the "
                + "controllers (endpoints, parameters, descriptions), a scripted scenario that calls every endpoint for real "
                + "(the request and response samples) and `docs/api-reference/intro.md` (the conventions at the top). "
                + "To change it, change the code or the intro, then regenerate - see `docs/api-reference/README.md`.\n\n");
        md.append(intro.strip()).append("\n\n");

        md.append("## Contents\n\n");
        md.append("- [Common error responses](#common-error-responses)\n");
        for (Map.Entry<String, List<Operation>> area : byArea.entrySet()) {
            md.append("- [").append(area.getKey()).append("](#").append(slug(area.getKey())).append(") - ")
                    .append(area.getValue().size()).append(" endpoints\n");
        }
        md.append('\n');

        renderCommonErrors(md);

        for (Map.Entry<String, List<Operation>> area : byArea.entrySet()) {
            md.append("---\n\n## ").append(area.getKey()).append("\n\n");
            String description = areaDescription(area.getKey());
            if (!description.isBlank()) {
                md.append(description.strip()).append("\n\n");
            }
            md.append("| Method | Path | What it does |\n|---|---|---|\n");
            for (Operation operation : area.getValue()) {
                md.append("| `").append(operation.method().toUpperCase()).append("` | [`")
                        .append(shortPath(operation.path())).append("`](#").append(anchor(operation)).append(") | ")
                        .append(escapeCell(operation.summary())).append(" |\n");
            }
            md.append('\n');
            for (Operation operation : area.getValue()) {
                renderOperation(md, operation);
            }
        }
        return md.toString().replace("\r\n", "\n").stripTrailing() + "\n";
    }

    private void renderCommonErrors(StringBuilder md) {
        md.append("## Common error responses\n\n");
        md.append("Every error is `application/problem+json` with a stable `code` (see the intro). Real examples:\n\n");
        for (Sample sample : commonSamples) {
            md.append("**").append(sample.title()).append("**\n\n");
            renderSample(md, sample);
        }
    }

    @SuppressWarnings("unchecked")
    private void renderOperation(StringBuilder md, Operation operation) {
        Map<String, Object> spec = operation.spec();
        md.append("<a id=\"").append(anchor(operation)).append("\"></a>\n\n");
        md.append("### `").append(operation.method().toUpperCase()).append(' ').append(shortPath(operation.path()))
                .append("` - ").append(operation.summary()).append("\n\n");

        String description = (String) spec.get("description");
        if (description != null && !description.isBlank()) {
            md.append(description.strip()).append("\n\n");
        }
        md.append("**Who can call it:** ").append(roles.getOrDefault(operation.key(), "ADMIN, MANAGER")).append("\n\n");

        List<Map<String, Object>> parameters = ((List<Map<String, Object>>) spec.getOrDefault("parameters", List.of()))
                .stream().filter(p -> !Boolean.TRUE.equals(p.get("hidden"))).toList();
        if (!parameters.isEmpty()) {
            md.append("| Parameter | In | Type | Required | Default | Notes |\n|---|---|---|---|---|---|\n");
            for (Map<String, Object> parameter : parameters) {
                Map<String, Object> schema = (Map<String, Object>) parameter.getOrDefault("schema", Map.of());
                Object defaultValue = schema.get("default");
                md.append("| `").append(parameter.get("name")).append("` | ").append(parameter.get("in")).append(" | ")
                        .append(escapeCell(typeOf(schema))).append(" | ")
                        .append(Boolean.TRUE.equals(parameter.get("required")) ? "yes" : "no").append(" | ")
                        .append(defaultValue == null ? "" : "`" + defaultValue + "`").append(" | ")
                        .append(escapeCell(noteFor(parameter))).append(" |\n");
            }
            md.append('\n');
        }

        Map<String, Object> requestBody = (Map<String, Object>) spec.get("requestBody");
        if (requestBody != null) {
            Map<String, Object> schema = bodySchema(requestBody);
            md.append("**Request body** (`application/json`): ").append(describeBody(schema)).append("\n\n");
            List<String[]> rows = fieldRows(schema);
            if (!rows.isEmpty()) {
                renderFieldTable(md, rows, true);
            }
        }

        Map<String, Object> responses = (Map<String, Object>) spec.getOrDefault("responses", Map.of());
        String success = responses.keySet().stream().filter(code -> code.startsWith("2")).sorted().findFirst().orElse(null);
        if (success != null) {
            Map<String, Object> response = (Map<String, Object>) responses.get(success);
            Map<String, Object> content = (Map<String, Object>) response.get("content");
            if (content != null && !content.isEmpty()) {
                Map<String, Object> media = (Map<String, Object>) content.values().iterator().next();
                Map<String, Object> schema = (Map<String, Object>) media.get("schema");
                List<String[]> rows = schema == null ? List.of() : fieldRows(schema);
                if (!rows.isEmpty()) {
                    md.append("<details><summary>Response fields</summary>\n\n");
                    renderFieldTable(md, rows, false);
                    md.append("</details>\n\n");
                }
            }
        }

        List<Sample> samples = operationSamples.stream().filter(s -> s.key().equals(operation.key())).toList();
        for (Sample sample : samples) {
            md.append("**Example - ").append(sample.title()).append("**\n\n");
            renderSample(md, sample);
        }
    }

    private void renderFieldTable(StringBuilder md, List<String[]> rows, boolean withRequired) {
        md.append(withRequired ? "| Field | Type | Required |\n|---|---|---|\n" : "| Field | Type |\n|---|---|\n");
        for (String[] row : rows) {
            md.append("| `").append(row[0]).append("` | ").append(escapeCell(row[1]));
            if (withRequired) {
                md.append(" | ").append(row[2]);
            }
            md.append(" |\n");
        }
        md.append('\n');
    }

    private void renderSample(StringBuilder md, Sample sample) {
        md.append("```http\n").append(sample.method()).append(' ').append(sample.path());
        if (!sample.query().isEmpty()) {
            md.append('?').append(sample.query().stream().map(q -> q[0] + "=" + q[1]).collect(Collectors.joining("&")));
        }
        md.append('\n');
        sample.requestHeaders().forEach((name, value) -> md.append(name).append(": ").append(value).append('\n'));
        if (sample.requestBody() != null) {
            md.append("Content-Type: application/json\n\n").append(sample.requestBody()).append('\n');
        }
        md.append("```\n\n");

        md.append("`").append(sample.status()).append(' ').append(STATUS_NAMES.getOrDefault(sample.status(), "")).append("`");
        if (sample.location() != null) {
            md.append(" - `Location: ").append(sample.location()).append('`');
        }
        if (sample.etag() != null) {
            md.append(" - `ETag: ").append(sample.etag()).append('`');
        }
        md.append("\n\n");
        if (sample.responseBody() != null) {
            md.append("```json\n").append(sample.responseBody()).append("\n```\n\n");
        }
    }

    // ------------------------------------------------------------------ schemas

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodySchema(Map<String, Object> requestBody) {
        Map<String, Object> content = (Map<String, Object>) requestBody.get("content");
        Map<String, Object> media = (Map<String, Object>) content.values().iterator().next();
        return (Map<String, Object>) media.getOrDefault("schema", Map.of());
    }

    private String describeBody(Map<String, Object> schema) {
        Map<String, Object> resolved = resolve(schema);
        if (isFreeForm(resolved)) {
            return "a JSON object - the fields are described above and shown in the examples.";
        }
        if ("array".equals(mainType(resolved))) {
            return "a JSON array of " + typeOf(items(resolved)) + ".";
        }
        return typeOf(schema) + ".";
    }

    private boolean isFreeForm(Map<String, Object> resolved) {
        return "object".equals(mainType(resolved)) && !resolved.containsKey("properties");
    }

    // Rows of {path, type, required} for an object schema (or an array of objects), nested up to MAX_DEPTH.
    private List<String[]> fieldRows(Map<String, Object> schema) {
        List<String[]> rows = new ArrayList<>();
        Map<String, Object> top = resolve(schema);
        String prefix = "";
        if ("array".equals(mainType(top))) {
            top = resolve(items(top));
            prefix = "[]";
        }
        collect(top, prefix, 0, new HashSet<>(), rows);
        return rows;
    }

    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> object, String prefix, int depth, Set<String> visiting, List<String[]> rows) {
        Map<String, Object> properties = properties(object);
        if (properties.isEmpty()) {
            return;
        }
        Set<String> required = new HashSet<>();
        Object requiredList = merged(object).get("required");
        if (requiredList instanceof List<?> list) {
            list.forEach(r -> required.add((String) r));
        }
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            Map<String, Object> raw = (Map<String, Object>) property.getValue();
            String path = prefix.isEmpty() ? property.getKey() : prefix + "." + property.getKey();
            String shown = path.startsWith("[].") ? path.substring(3) : path;
            rows.add(new String[]{path.equals(shown) ? path : "[]." + shown, typeOf(raw),
                    required.contains(property.getKey()) ? "yes" : "no"});

            Map<String, Object> child = resolve(raw);
            String nested = path;
            if ("array".equals(mainType(child))) {
                child = resolve(items(child));
                nested = path + "[]";
            }
            String id = refName(raw) == null ? refName(items(resolve(raw))) : refName(raw);
            if (!properties(child).isEmpty() && depth < MAX_DEPTH && (id == null || visiting.add(id))) {
                collect(child, nested, depth + 1, visiting, rows);
                if (id != null) {
                    visiting.remove(id);
                }
            }
        }
    }

    // Properties of a schema, including those contributed by allOf members.
    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> schema) {
        Map<String, Object> flat = merged(schema);
        Object properties = flat.get("properties");
        return properties instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> merged(Map<String, Object> schema) {
        Map<String, Object> resolved = resolve(schema);
        Object allOf = resolved.get("allOf");
        if (!(allOf instanceof List<?> parts)) {
            return resolved;
        }
        Map<String, Object> result = new LinkedHashMap<>(resolved);
        Map<String, Object> properties = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (Object part : parts) {
            Map<String, Object> member = merged((Map<String, Object>) part);
            if (member.get("properties") instanceof Map<?, ?> props) {
                properties.putAll((Map<String, Object>) props);
            }
            if (member.get("required") instanceof List<?> req) {
                required.addAll(req);
            }
        }
        if (resolved.get("properties") instanceof Map<?, ?> own) {
            properties.putAll((Map<String, Object>) own);
        }
        result.put("properties", properties);
        result.put("required", required);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolve(Map<String, Object> schema) {
        Map<String, Object> current = schema == null ? Map.of() : schema;
        for (int i = 0; i < 10 && current.containsKey("$ref"); i++) {
            String name = refName(current);
            Map<String, Object> components = (Map<String, Object>) ((Map<String, Object>) apiDocs.get("components")).get("schemas");
            Object target = components.get(name);
            if (target == null) {
                return Map.of();
            }
            current = (Map<String, Object>) target;
        }
        // A nullable wrapper: anyOf/oneOf of one real schema and {"type":"null"}.
        for (String key : List.of("anyOf", "oneOf")) {
            if (current.get(key) instanceof List<?> options) {
                List<Map<String, Object>> real = new ArrayList<>();
                for (Object option : options) {
                    Map<String, Object> o = (Map<String, Object>) option;
                    if (!"null".equals(o.get("type"))) {
                        real.add(o);
                    }
                }
                if (real.size() == 1) {
                    return resolve(real.get(0));
                }
            }
        }
        return current;
    }

    private String refName(Map<String, Object> schema) {
        if (schema == null || !(schema.get("$ref") instanceof String ref)) {
            return null;
        }
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> items(Map<String, Object> schema) {
        Object items = schema == null ? null : schema.get("items");
        return items instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    // The schema's type, ignoring "null" (OpenAPI 3.1 writes nullable as ["string","null"]).
    private String mainType(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(t -> !t.equals("null")).findFirst().orElse(null);
        }
        if (type == null && (schema.containsKey("properties") || schema.get("allOf") != null)) {
            return "object";
        }
        return type == null ? null : String.valueOf(type);
    }

    private String typeOf(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return "any";
        }
        boolean nullable = false;
        Object type = schema.get("type");
        if (type instanceof List<?> list && list.contains("null")) {
            nullable = true;
        }
        if (Boolean.TRUE.equals(schema.get("nullable"))) {
            nullable = true;
        }
        Map<String, Object> resolved = resolve(schema);
        String text;
        if (schema.get("$ref") instanceof String && !properties(resolved).isEmpty()) {
            text = "object";      // the service's internal class name means nothing to a caller
        } else if (schema.get("anyOf") instanceof List<?> || schema.get("oneOf") instanceof List<?>) {
            text = typeOf(resolved);
            nullable = true;
        } else {
            String main = mainType(resolved);
            if ("array".equals(main)) {
                text = "array of " + typeOf(items(resolved));
            } else if (resolved.get("enum") instanceof List<?> values) {
                text = "enum: " + values.stream().map(String::valueOf).collect(Collectors.joining(" / "));
            } else if (main == null) {
                text = "any";
            } else if ("object".equals(main) && !resolved.containsKey("properties")) {
                text = "object";
            } else {
                String format = (String) resolved.get("format");
                boolean plain = format == null || format.startsWith("int") || format.equals("double") || format.equals("float");
                text = plain ? main : main + " (" + format + ")";
            }
        }
        return nullable && !text.endsWith("or null") ? text + " or null" : text;
    }

    // ------------------------------------------------------------------ small helpers

    @SuppressWarnings("unchecked")
    private String areaDescription(String tag) {
        Object tags = apiDocs.get("tags");
        if (tags instanceof List<?> list) {
            for (Object entry : list) {
                Map<String, Object> map = (Map<String, Object>) entry;
                if (tag.equals(map.get("name"))) {
                    return (String) map.getOrDefault("description", "");
                }
            }
        }
        return "";
    }

    // The parameter's own description, or a standard note for the ones every list shares.
    private static String noteFor(Map<String, Object> parameter) {
        String description = (String) parameter.getOrDefault("description", "");
        if (!description.isBlank()) {
            return description;
        }
        return switch ((String) parameter.get("name")) {
            case "page" -> "Page number, starting at 0";
            case "size" -> "Rows per page (1 to 200)";
            case "q" -> "Case-insensitive text search";
            case "at" -> "Evaluate as of this date (default today)";
            case "active" -> "Keep only active (true) or inactive (false) records";
            case "id" -> "The record's id";
            case "includeInactive" -> "Include soft-deleted items";
            default -> "";
        };
    }

    private static String shortPath(String path) {
        return path.replaceFirst("^/api/v1/master-data", "");
    }

    private static String anchor(Operation operation) {
        return slug(operation.method() + " " + shortPath(operation.path()));
    }

    static String slug(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static String escapeCell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    // Used by the test to say which operations have no sample yet.
    Set<String> sampledKeys() {
        return operationSamples.stream().map(Sample::key).collect(Collectors.toCollection(java.util.TreeSet::new));
    }
}
