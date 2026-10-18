package lk.serenetravels.masterdataservice.util;

import java.sql.SQLException;

public final class SqlErrors {

    public static final String FOREIGN_KEY_VIOLATION = "23503";

    private SqlErrors() {
    }

    // The SQLSTATE of the first SQLException in the cause chain, or null. Matching on the code
    // (not the message text) keeps error mapping independent of the database's wording.
    public static String sqlState(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
