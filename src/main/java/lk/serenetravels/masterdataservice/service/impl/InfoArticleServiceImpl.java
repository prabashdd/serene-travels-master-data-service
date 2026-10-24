package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.InfoArticleResponse;
import lk.serenetravels.masterdataservice.dto.InfoArticleSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.InfoArticleService;
import lk.serenetravels.masterdataservice.util.ColumnValues;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Column;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Type;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InfoArticleServiceImpl implements InfoArticleService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_TITLE = 200;
    private static final int MAX_BODY = 20_000;
    private static final int EXCERPT_LENGTH = 140;
    // Mirrors the CHECK constraint on info_article.category.
    private static final List<String> CATEGORIES = List.of("TRAVEL_TIP", "VISA", "WEATHER", "GENERAL");

    private static final List<Column> COLUMNS = List.of(
            new Column("category", "category", Type.TEXT, true),
            new Column("destinationId", "destination_id", Type.INT, false),
            new Column("title", "title", Type.TEXT, true),
            new Column("body", "body", Type.TEXT, true));

    private static final Map<String, String> SORTS = Map.of("title", "a.title", "category", "a.category",
            "destination", "d.name", "id", "a.id");

    private static final String FROM = " from info_article a left join destination d on d.id = a.destination_id";

    private final JdbcClient jdbc;

    public InfoArticleServiceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<InfoArticleSummaryResponse> list(String q, String category, Integer destinationId,
                                                         boolean includeCountryWide, Boolean countryWide,
                                                         List<String> sort, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        String categoryFilter = category == null || category.isBlank() ? null : category.trim().toUpperCase();
        if (categoryFilter != null && !CATEGORIES.contains(categoryFilter)) {
            throw InvalidRequestException.field("category", "must be one of " + CATEGORIES);
        }
        Map<String, Object> params = new HashMap<>();
        List<String> where = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.add("(lower(a.title) like :q escape '\\' or lower(a.body) like :q escape '\\')");
            params.put("q", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (categoryFilter != null) {
            where.add("a.category = :category");
            params.put("category", categoryFilter);
        }
        if (destinationId != null) {
            where.add(includeCountryWide ? "(a.destination_id = :destinationId or a.destination_id is null)"
                    : "a.destination_id = :destinationId");
            params.put("destinationId", destinationId);
        }
        if (countryWide != null) {
            where.add(countryWide ? "a.destination_id is null" : "a.destination_id is not null");
        }
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Long total = jdbc.sql("select count(*)" + FROM + whereSql).params(params).query(Long.class).single();

        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<InfoArticleSummaryResponse> rows = jdbc.sql("""
                        select a.id, a.category, a.destination_id, d.name as destination_name, a.title, a.body"""
                        + FROM + whereSql + " order by " + orderBy(sort) + " limit :limit offset :offset")
                .params(params).query((rs, n) -> {
                    String body = rs.getString("body");
                    Integer destination = rs.getObject("destination_id", Integer.class);
                    return new InfoArticleSummaryResponse(rs.getInt("id"), rs.getString("category"),
                            destination == null ? null : new RefResponse(destination, rs.getString("destination_name")),
                            rs.getString("title"), excerpt(body), body.length());
                }).list();
        return PageResponse.of(rows, total, page, size);
    }

    @Override
    public InfoArticleResponse get(int id) {
        return jdbc.sql("select a.id, a.category, a.destination_id, d.name as destination_name, a.title, a.body"
                        + FROM + " where a.id = :id").param("id", id)
                .query((rs, n) -> {
                    Integer destination = rs.getObject("destination_id", Integer.class);
                    return new InfoArticleResponse(rs.getInt("id"), rs.getString("category"),
                            destination == null ? null : new RefResponse(destination, rs.getString("destination_name")),
                            rs.getString("title"), rs.getString("body"));
                }).optional().orElseThrow(() -> notFound(id));
    }

    // ------------------------------------------------------------------ writes

    @Override
    @Transactional
    public InfoArticleResponse create(Map<String, Object> body) {
        Map<String, Object> values = ColumnValues.coerce("info article", COLUMNS, body, true);
        normalise(values);
        validate(values);
        checkNotDuplicate(values, null);

        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            columns.add(column(json).db());
            placeholders.add(":" + json);
            params.put(json, value);
        });
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into info_article (" + String.join(", ", columns) + ") values ("
                + String.join(", ", placeholders) + ")").params(params).update(keyHolder, "id");
        return get(keyHolder.getKey().intValue());
    }

    @Override
    @Transactional
    public InfoArticleResponse update(int id, Map<String, Object> body) {
        InfoArticleResponse existing = get(id);
        Map<String, Object> values = ColumnValues.coerce("info article", COLUMNS, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }
        normalise(values);

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("category", existing.category());
        merged.put("destinationId", existing.destination() == null ? null : existing.destination().id());
        merged.put("title", existing.title());
        merged.put("body", existing.body());
        merged.putAll(values);
        validate(merged);
        if (values.containsKey("category") || values.containsKey("destinationId") || values.containsKey("title")) {
            checkNotDuplicate(merged, id);
        }

        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            assignments.add(column(json).db() + " = :" + json);
            params.put(json, value);
        });
        params.put("rowId", id);
        jdbc.sql("update info_article set " + String.join(", ", assignments) + " where id = :rowId").params(params).update();
        return get(id);
    }

    @Override
    @Transactional
    public void delete(int id) {
        int deleted = jdbc.sql("delete from info_article where id = :id").param("id", id).update();
        if (deleted == 0) {
            throw notFound(id);
        }
    }

    // ------------------------------------------------------------------ validation

    private void normalise(Map<String, Object> values) {
        if (values.get("category") instanceof String text) {
            values.put("category", text.toUpperCase());
        }
    }

    // `values` is a complete article (create) or the merged row (update), keyed by JSON field name.
    private void validate(Map<String, Object> values) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!CATEGORIES.contains(values.get("category"))) {
            errors.put("category", "must be one of " + CATEGORIES);
        }
        if (values.get("title") instanceof String title && title.length() > MAX_TITLE) {
            errors.put("title", "must be at most " + MAX_TITLE + " characters");
        }
        if (values.get("body") instanceof String text && text.length() > MAX_BODY) {
            errors.put("body", "must be at most " + MAX_BODY + " characters");
        }
        if (values.get("destinationId") instanceof Integer destinationId
                && jdbc.sql("select count(*) from destination where id = :id").param("id", destinationId)
                .query(Long.class).single() == 0) {
            errors.put("destinationId", "is not a known destination");
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
    }

    // The same title in the same category for the same destination (or both country-wide) is an accidental repeat.
    private void checkNotDuplicate(Map<String, Object> values, Integer selfId) {
        Object destinationId = values.get("destinationId");
        Map<String, Object> params = new HashMap<>();
        params.put("category", values.get("category"));
        params.put("title", ((String) values.get("title")).toLowerCase());
        params.put("self", selfId == null ? -1 : selfId);
        String destinationCondition = "destination_id is null";
        if (destinationId != null) {
            destinationCondition = "destination_id = :destinationId";
            params.put("destinationId", destinationId);
        }
        Long clash = jdbc.sql("select count(*) from info_article where category = :category and lower(title) = :title "
                        + "and id <> :self and " + destinationCondition)
                .params(params).query(Long.class).single();
        if (clash > 0) {
            throw new ConflictException("DUPLICATE", "There is already a " + values.get("category")
                    + " article titled '" + values.get("title") + "' for this destination");
        }
    }

    // ------------------------------------------------------------------ helpers

    // The first ~140 characters of the body on one line. Many bodies open with the place name in capitals
    // ("TANGALLE"), which only repeats the title, so a short all-caps first line is skipped.
    static String excerpt(String body) {
        String text = body.strip();
        int newline = text.indexOf('\n');
        if (newline > 0 && newline <= 60) {
            String first = text.substring(0, newline).strip();
            if (!first.isEmpty() && first.equals(first.toUpperCase()) && text.length() > newline + 1) {
                text = text.substring(newline + 1).strip();
            }
        }
        text = text.replaceAll("\\s+", " ");
        return text.length() <= EXCERPT_LENGTH ? text : text.substring(0, EXCERPT_LENGTH).stripTrailing() + "…";
    }

    private String orderBy(List<String> sort) {
        List<String> parts = new ArrayList<>();
        if (sort != null) {
            for (String entry : sort) {
                String[] bits = entry.split(",");
                String sql = SORTS.get(bits[0].trim());
                String direction = bits.length > 1 ? bits[1].trim().toLowerCase() : "asc";
                if (sql == null || bits.length > 2 || !(direction.equals("asc") || direction.equals("desc"))) {
                    throw InvalidRequestException.field("sort",
                            "'" + entry + "' is not valid; use field,asc|desc with field one of " + SORTS.keySet());
                }
                parts.add(sql + " " + direction);
            }
        }
        if (parts.isEmpty()) {
            parts.add("a.title asc");
        }
        parts.add("a.id asc");
        return String.join(", ", parts);
    }

    private Column column(String json) {
        return COLUMNS.stream().filter(c -> c.json().equals(json)).findFirst().orElseThrow();
    }

    private NotFoundException notFound(int id) {
        return new NotFoundException("No info article found with id '" + id + "'");
    }
}
