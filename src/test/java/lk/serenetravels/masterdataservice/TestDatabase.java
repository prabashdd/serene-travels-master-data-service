package lk.serenetravels.masterdataservice;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

// A fresh in-memory H2 database (PostgreSQL mode) per call, so tests never share state and never
// touch the real tour-management database.
public final class TestDatabase {

    private TestDatabase() {
    }

    public static String newUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    public static JdbcClient fresh() {
        DataSource dataSource = new DriverManagerDataSource(newUrl(), "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("test-schema.sql"));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not create the test schema", ex);
        }
        return JdbcClient.create(dataSource);
    }
}
