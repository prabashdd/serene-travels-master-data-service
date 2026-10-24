package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.GuideResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.GuideService;
import lk.serenetravels.masterdataservice.util.Coerce;
import lk.serenetravels.masterdataservice.util.ColumnValues;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Column;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Type;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class GuideServiceImpl implements GuideService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final List<String> GUIDE_TYPES = List.of("DRIVER", "CHAUFFEUR_GUIDE", "NATIONAL_GUIDE");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final List<Column> COLUMNS = List.of(
            new Column("guideNo", "guide_no", Type.TEXT, true),
            new Column("name", "name", Type.TEXT, true),
            new Column("guideType", "guide_type", Type.TEXT, true),
            new Column("nickname", "nickname", Type.TEXT, false),
            new Column("nic", "nic", Type.TEXT, false),
            new Column("contact", "contact", Type.TEXT, false),
            new Column("email", "email", Type.TEXT, false),
            new Column("address", "address", Type.TEXT, false),
            new Column("town", "town", Type.TEXT, false),
            new Column("active", "active", Type.BOOL, false));

    private static final Map<String, String> SORTS = Map.of(
            "name", "g.name", "guideNo", "g.guide_no", "guideType", "g.guide_type", "town", "g.town", "id", "g.id");

    private static final String SELECT = """
            select g.id, g.guide_no, g.name, g.nickname, g.guide_type, g.nic, g.contact, g.email, g.address, g.town, g.active
              from guide g""";

    private final JdbcClient jdbc;

    public GuideServiceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<GuideResponse> list(String q, String guideType, Integer languageId, Boolean active,
                                            List<String> sort, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        if (guideType != null && !GUIDE_TYPES.contains(guideType)) {
            throw InvalidRequestException.field("guideType", "must be one of " + GUIDE_TYPES);
        }
        Map<String, Object> params = new HashMap<>();
        List<String> where = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.add("(lower(g.name) like :q escape '\\' or lower(g.nickname) like :q escape '\\' "
                    + "or lower(g.guide_no) like :q escape '\\' or lower(g.nic) like :q escape '\\' "
                    + "or lower(g.contact) like :q escape '\\' or lower(g.email) like :q escape '\\' "
                    + "or lower(g.town) like :q escape '\\')");
            params.put("q", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (guideType != null) {
            where.add("g.guide_type = :guideType");
            params.put("guideType", guideType);
        }
        if (languageId != null) {
            where.add("exists (select 1 from guide_language gl where gl.guide_id = g.id and gl.language_id = :languageId)");
            params.put("languageId", languageId);
        }
        if (active != null) {
            where.add("g.active = :active");
            params.put("active", active);
        }
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Long total = jdbc.sql("select count(*) from guide g" + whereSql).params(params).query(Long.class).single();

        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<GuideResponse> rows = jdbc.sql(SELECT + whereSql + " order by " + orderBy(sort) + " limit :limit offset :offset")
                .params(params).query((rs, n) -> map(rs)).list();
        return PageResponse.of(withLanguages(rows), total, page, size);
    }

    @Override
    public GuideResponse get(int id) {
        GuideResponse row = jdbc.sql(SELECT + " where g.id = :id").param("id", id).query((rs, n) -> map(rs))
                .optional().orElseThrow(() -> notFound(id));
        return withLanguages(List.of(row)).get(0);
    }

    // ------------------------------------------------------------------ writes

    @Override
    @Transactional
    public GuideResponse create(Map<String, Object> body) {
        Map<String, Object> fields = new LinkedHashMap<>(body);
        Object rawLanguages = fields.remove("languageIds");

        Map<String, Object> values = ColumnValues.coerce("guide", COLUMNS, fields, true);
        normalise(values);
        values.putIfAbsent("active", Boolean.TRUE);
        List<Integer> languageIds = rawLanguages == null ? List.of() : parseLanguageIds(rawLanguages, "languageIds");
        validate(values, values, null);
        checkLanguagesExist(languageIds, "languageIds");

        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            columns.add(column(json).db());
            placeholders.add(":" + json);
            params.put(json, value);
        });
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into guide (" + String.join(", ", columns) + ") values (" + String.join(", ", placeholders) + ")")
                .params(params).update(keyHolder, "id");
        int id = keyHolder.getKey().intValue();
        insertLanguages(id, languageIds);
        return get(id);
    }

    @Override
    @Transactional
    public GuideResponse update(int id, Map<String, Object> body) {
        GuideResponse existing = get(id);
        Map<String, Object> values = ColumnValues.coerce("guide", COLUMNS, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }
        normalise(values);

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("guideNo", existing.guideNo());
        merged.put("name", existing.name());
        merged.put("guideType", existing.guideType());
        merged.put("nic", existing.nic());
        merged.put("active", existing.active());
        merged.putAll(values);
        validate(values, merged, id);

        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            assignments.add(column(json).db() + " = :" + json);
            params.put(json, value);
        });
        params.put("rowId", id);
        jdbc.sql("update guide set " + String.join(", ", assignments) + " where id = :rowId").params(params).update();
        return get(id);
    }

    @Override
    @Transactional
    public void deactivate(int id) {
        int updated = jdbc.sql("update guide set active = false where id = :id").param("id", id).update();
        if (updated == 0) {
            throw notFound(id);
        }
    }

    @Override
    @Transactional
    public List<RefResponse> replaceLanguages(int guideId, List<Integer> languageIds) {
        get(guideId);
        if (languageIds == null) {
            throw new InvalidRequestException("The request body must be a list of language ids (use [] to clear it)");
        }
        List<Integer> ids = parseLanguageIds(languageIds, "languageIds");
        checkLanguagesExist(ids, "languageIds");

        jdbc.sql("delete from guide_language where guide_id = :id").param("id", guideId).update();
        insertLanguages(guideId, ids);
        return languages(List.of(guideId)).getOrDefault(guideId, List.of());
    }

    // ------------------------------------------------------------------ validation

    private void normalise(Map<String, Object> values) {
        if (values.get("guideType") instanceof String text) {
            values.put("guideType", text.toUpperCase());
        }
        if (values.get("guideNo") instanceof String text) {
            values.put("guideNo", text.toUpperCase());
        }
    }

    // `changed` = the fields being written; `merged` = the complete row as it will look (same map on create).
    private void validate(Map<String, Object> changed, Map<String, Object> merged, Integer selfId) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (merged.get("active") == null) {
            errors.put("active", "must be true or false");
        }
        if (merged.get("guideType") == null || !GUIDE_TYPES.contains(merged.get("guideType"))) {
            errors.put("guideType", "must be one of " + GUIDE_TYPES);
        }
        if (changed.get("email") instanceof String email && !EMAIL.matcher(email).matches()) {
            errors.put("email", "is not a valid email address");
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }

        Long guideNoClash = jdbc.sql("select count(*) from guide where upper(trim(guide_no)) = :no and id <> :self")
                .param("no", ((String) merged.get("guideNo")).trim().toUpperCase())
                .param("self", selfId == null ? -1 : selfId).query(Long.class).single();
        if (guideNoClash > 0) {
            throw new ConflictException("DUPLICATE", "Guide number '" + merged.get("guideNo") + "' is already used");
        }
        if (merged.get("nic") instanceof String nic) {
            Long nicClash = jdbc.sql("select count(*) from guide where lower(trim(nic)) = :nic and id <> :self")
                    .param("nic", nic.trim().toLowerCase())
                    .param("self", selfId == null ? -1 : selfId).query(Long.class).single();
            if (nicClash > 0) {
                throw new ConflictException("DUPLICATE", "NIC '" + nic + "' already belongs to another guide");
            }
        }
    }

    private List<Integer> parseLanguageIds(Object raw, String field) {
        if (!(raw instanceof List<?> list)) {
            throw InvalidRequestException.field(field, "must be a list of language ids");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                Integer id = Coerce.toInt(list.get(i), field + "[" + i + "]");
                if (!seen.add(id)) {
                    errors.put(field + "[" + i + "]", "is listed more than once");
                }
                ids.add(id);
            } catch (InvalidRequestException ex) {
                errors.putAll(ex.getFieldErrors());
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
        return ids;
    }

    private void checkLanguagesExist(List<Integer> ids, String field) {
        if (ids.isEmpty()) {
            return;
        }
        Set<Integer> existing = new HashSet<>(jdbc.sql("select id from language where id in (:ids)").param("ids", ids)
                .query(Integer.class).list());
        Map<String, String> errors = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            if (!existing.contains(ids.get(i))) {
                errors.put(field + "[" + i + "]", "is not a known language");
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void insertLanguages(int guideId, List<Integer> languageIds) {
        for (Integer languageId : languageIds) {
            jdbc.sql("insert into guide_language (guide_id, language_id) values (:guide, :language)")
                    .param("guide", guideId).param("language", languageId).update();
        }
    }

    // One query for the whole page: the languages each guide speaks, in language-id order (English first).
    private Map<Integer, List<RefResponse>> languages(List<Integer> guideIds) {
        Map<Integer, List<RefResponse>> byGuide = new HashMap<>();
        jdbc.sql("""
                        select gl.guide_id, l.id, l.name from guide_language gl join language l on l.id = gl.language_id
                         where gl.guide_id in (:ids) order by gl.guide_id, l.id""")
                .param("ids", guideIds)
                .query((rs, n) -> {
                    byGuide.computeIfAbsent(rs.getInt("guide_id"), k -> new ArrayList<>())
                            .add(new RefResponse(rs.getInt("id"), rs.getString("name")));
                    return null;
                }).list();
        return byGuide;
    }

    private List<GuideResponse> withLanguages(List<GuideResponse> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<Integer, List<RefResponse>> byGuide = languages(rows.stream().map(GuideResponse::id).toList());
        return rows.stream().map(r -> new GuideResponse(r.id(), r.guideNo(), r.name(), r.nickname(), r.guideType(), r.nic(),
                r.contact(), r.email(), r.address(), r.town(), r.active(), byGuide.getOrDefault(r.id(), List.of())))
                .collect(Collectors.toList());
    }

    private GuideResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GuideResponse(rs.getInt("id"), rs.getString("guide_no"), rs.getString("name"), rs.getString("nickname"),
                rs.getString("guide_type"), rs.getString("nic"), rs.getString("contact"), rs.getString("email"),
                rs.getString("address"), rs.getString("town"), rs.getBoolean("active"), List.of());
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
            parts.add("g.name asc");
        }
        parts.add("g.id asc");
        return String.join(", ", parts);
    }

    private Column column(String json) {
        return COLUMNS.stream().filter(c -> c.json().equals(json)).findFirst().orElseThrow();
    }

    private NotFoundException notFound(int id) {
        return new NotFoundException("No guide found with id '" + id + "'");
    }
}
