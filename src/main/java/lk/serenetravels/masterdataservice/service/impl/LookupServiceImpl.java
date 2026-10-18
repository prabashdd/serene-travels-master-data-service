package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.LookupService;
import lk.serenetravels.masterdataservice.util.Coerce;
import lk.serenetravels.masterdataservice.util.LookupDefinitions;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Column;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Lookup;
import lk.serenetravels.masterdataservice.util.SqlErrors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LookupServiceImpl implements LookupService {

    private final JdbcClient jdbc;

    public LookupServiceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, List<Map<String, Object>>> bundle(boolean includeInactive) {
        Map<String, List<Map<String, Object>>> bundle = new LinkedHashMap<>();
        for (Lookup lookup : LookupDefinitions.all()) {
            bundle.put(lookup.bundleKey(), query(lookup, includeInactive));
        }
        return bundle;
    }

    @Override
    public List<Map<String, Object>> list(String name, boolean includeInactive) {
        return query(LookupDefinitions.require(name), includeInactive);
    }

    @Override
    @Transactional
    public Map<String, Object> create(String name, Map<String, Object> body) {
        Lookup lookup = LookupDefinitions.require(name);
        Map<String, Object> values = coerce(lookup, body, true);

        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            columns.add(lookup.column(json).db());
            placeholders.add(":" + json);
            params.put(json, value);
        });

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into " + lookup.table() + " (" + String.join(", ", columns) + ") values ("
                + String.join(", ", placeholders) + ")").params(params).update(keyHolder, "id");
        return get(lookup, keyHolder.getKey().intValue());
    }

    @Override
    @Transactional
    public Map<String, Object> update(String name, int id, Map<String, Object> body) {
        Lookup lookup = LookupDefinitions.require(name);
        Map<String, Object> values = coerce(lookup, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }

        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            assignments.add(lookup.column(json).db() + " = :" + json);
            params.put(json, value);
        });
        params.put("id", id);

        int updated = jdbc.sql("update " + lookup.table() + " set " + String.join(", ", assignments)
                + " where id = :id").params(params).update();
        if (updated == 0) {
            throw notFound(lookup, id);
        }
        return get(lookup, id);
    }

    @Override
    @Transactional
    public void delete(String name, int id) {
        Lookup lookup = LookupDefinitions.require(name);
        int affected;
        if (lookup.softDeleteColumn() != null) {
            affected = jdbc.sql("update " + lookup.table() + " set " + lookup.softDeleteColumn()
                    + " = false where id = :id").param("id", id).update();
        } else {
            try {
                affected = jdbc.sql("delete from " + lookup.table() + " where id = :id").param("id", id).update();
            } catch (DataIntegrityViolationException ex) {
                if (SqlErrors.FOREIGN_KEY_VIOLATION.equals(SqlErrors.sqlState(ex))) {
                    throw new ConflictException("IN_USE", "This " + singular(lookup)
                            + " is still referenced by other master data and cannot be deleted");
                }
                throw ex;
            }
        }
        if (affected == 0) {
            throw notFound(lookup, id);
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> query(Lookup lookup, boolean includeInactive) {
        String where = "";
        if (lookup.softDeleteColumn() != null && !includeInactive) {
            where = " where " + lookup.softDeleteColumn() + " = true";
        }
        return jdbc.sql("select " + selectColumns(lookup) + " from " + lookup.table() + where
                + " order by " + lookup.orderBy()).query(rowMapper(lookup)).list();
    }

    private Map<String, Object> get(Lookup lookup, int id) {
        return jdbc.sql("select " + selectColumns(lookup) + " from " + lookup.table() + " where id = :id")
                .param("id", id).query(rowMapper(lookup)).optional().orElseThrow(() -> notFound(lookup, id));
    }

    private String selectColumns(Lookup lookup) {
        return "id, " + lookup.columns().stream().map(Column::db).collect(Collectors.joining(", "));
    }

    private RowMapper<Map<String, Object>> rowMapper(Lookup lookup) {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getInt("id"));
            for (Column column : lookup.columns()) {
                row.put(column.json(), switch (column.type()) {
                    case TEXT -> rs.getString(column.db());
                    case INT -> rs.getObject(column.db(), Integer.class);
                    case DATE -> rs.getObject(column.db(), LocalDate.class);
                    case BOOL -> rs.getObject(column.db(), Boolean.class);
                });
            }
            return row;
        };
    }

    // Unknown fields are rejected (a typo should not silently do nothing); on create every required
    // field must be present, and on update a required field can be changed but never blanked.
    private Map<String, Object> coerce(Lookup lookup, Map<String, Object> body, boolean creating) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();

        for (String field : body.keySet()) {
            if (lookup.column(field) == null) {
                errors.put(field, "is not a field of " + lookup.name());
            }
        }

        for (Column column : lookup.columns()) {
            Object raw = body.get(column.json());
            boolean blank = raw == null || (raw instanceof String text && text.isBlank());
            if (blank) {
                if (column.required() && (creating || body.containsKey(column.json()))) {
                    errors.put(column.json(), "is required");
                } else if (!column.required() && body.containsKey(column.json())) {
                    values.put(column.json(), null);
                }
                continue;
            }
            try {
                values.put(column.json(), switch (column.type()) {
                    case TEXT -> Coerce.toText(raw, column.json());
                    case INT -> Coerce.toInt(raw, column.json());
                    case DATE -> Coerce.toDate(raw, column.json());
                    case BOOL -> Coerce.toBool(raw, column.json());
                });
            } catch (InvalidRequestException ex) {
                errors.putAll(ex.getFieldErrors());
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
        return values;
    }

    private NotFoundException notFound(Lookup lookup, int id) {
        return new NotFoundException("No " + singular(lookup) + " found with id '" + id + "'");
    }

    private String singular(Lookup lookup) {
        return lookup.name().replace('-', ' ').replaceFirst("ies$", "y").replaceFirst("s$", "");
    }
}
