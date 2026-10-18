package lk.serenetravels.masterdataservice.util;

import lk.serenetravels.masterdataservice.exception.InvalidRequestException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

// Turns loosely-typed JSON / query-string values into the Java type a column needs, failing with a
// field-level validation error (not a 500) when the value does not fit.
public final class Coerce {

    private Coerce() {
    }

    public static Integer toInt(Object value, String field) {
        if (value instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString());
            try {
                return decimal.intValueExact();
            } catch (ArithmeticException ex) {
                throw InvalidRequestException.field(field, "must be a whole number");
            }
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ex) {
                throw InvalidRequestException.field(field, "must be a whole number");
            }
        }
        throw InvalidRequestException.field(field, "must be a whole number");
    }

    public static String toText(Object value, String field) {
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        throw InvalidRequestException.field(field, "must be a non-blank text value");
    }

    public static LocalDate toDate(Object value, String field) {
        if (value instanceof String text) {
            try {
                return LocalDate.parse(text.trim());
            } catch (DateTimeParseException ex) {
                // fall through to the shared message below
            }
        }
        throw InvalidRequestException.field(field, "must be a date in YYYY-MM-DD format");
    }

    public static Boolean toBool(Object value, String field) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw InvalidRequestException.field(field, "must be true or false");
    }
}
