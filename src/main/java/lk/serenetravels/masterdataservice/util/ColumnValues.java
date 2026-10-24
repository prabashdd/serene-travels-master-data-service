package lk.serenetravels.masterdataservice.util;

import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Column;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Turns a loosely-typed JSON object into column values, according to a fixed list of column
// definitions. Unknown fields are rejected (a typo should not silently do nothing); on create every
// required field must be present, and on update a required field can be changed but never blanked.
// All problems are reported together as per-field errors.
public final class ColumnValues {

    private ColumnValues() {
    }

    /**
     * @param owner    what the body describes, used in "is not a field of ..." messages
     * @param creating true for a create (required fields must be present), false for a partial update
     * @return JSON field name -> coerced value, for the fields that should be written
     */
    public static Map<String, Object> coerce(String owner, List<Column> columns, Map<String, Object> body,
                                             boolean creating) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();

        for (String field : body.keySet()) {
            if (columns.stream().noneMatch(c -> c.json().equals(field))) {
                errors.put(field, "is not a field of " + owner);
            }
        }

        for (Column column : columns) {
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
                    case DECIMAL -> Coerce.toDecimal(raw, column.json());
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
}
