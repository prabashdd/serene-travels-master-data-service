package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RateRowResponse;
import lk.serenetravels.masterdataservice.dto.RateWriteRequest;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.service.impl.LookupServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.RateServiceImpl;
import lk.serenetravels.masterdataservice.util.RateFamilies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Opt-in check of the SQL against the REAL tour-management PostgreSQL schema, which H2 cannot fully
// reproduce (partial unique indexes, identity columns, numeric scale, FOR UPDATE, SQLSTATEs):
//
//   mvnw test -Dtest=LivePostgresSmokeTest -Dlive.db=true
//
// It never changes real data. Reads hit the real tables; every write runs on ONE connection after
// CREATE TEMP TABLE shadows the target tables. A temp table is private to the session and takes
// precedence over the real table of the same name, so the service's unqualified SQL lands in the
// copy (which keeps the real constraints and partial indexes) and vanishes when the connection closes.
@EnabledIfSystemProperty(named = "live.db", matches = "true")
class LivePostgresSmokeTest {

    private static final String URL = System.getProperty("live.db.url", "jdbc:postgresql://localhost:5432/tour-management");

    @Test
    void readsRealDataAndWritesOnlyToSessionLocalCopies() throws Exception {
        try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, "postgres", "postgres", true)) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            Clock clock = Clock.system(ZoneId.of("Asia/Colombo"));
            RateServiceImpl rates = new RateServiceImpl(jdbc, clock);
            LookupServiceImpl lookups = new LookupServiceImpl(jdbc);

            long realVehicleRates = count(jdbc, "public.vehicle_rate");
            long realPropertyRates = count(jdbc, "public.property_rate");

            // ---- reads against real data
            assertThat(lookups.bundle(false)).hasSize(11);
            assertThat(lookups.list("vehicle-types", false)).isNotEmpty();
            assertThat(lookups.list("seasons", false)).hasSize(3);

            for (RateFamilies.Family family : RateFamilies.all()) {
                PageResponse<RateRowResponse> page = rates.list(family.name(), Map.of(), null, true, 0, 5);
                assertThat(page.totalElements()).as(family.name()).isGreaterThanOrEqualTo(0);
            }

            // The rates loaded earlier from the business's per-km figures (LKR 70/108/120/150).
            List<RateRowResponse> vehicle = rates.list("vehicle", Map.of(), null, false, 0, 50).content();
            assertThat(vehicle).hasSize((int) realVehicleRates);
            assertThat(vehicle).extracting(r -> r.labels().get("vehicleTypeId")).contains("Car", "Mini Coach");
            assertThat(vehicle).filteredOn(r -> "Car".equals(r.labels().get("vehicleTypeId")))
                    .allSatisfy(r -> assertThat(r.rate()).isEqualByComparingTo("70"));

            // ---- writes against session-local shadows only
            jdbc.sql("create temp table vehicle_rate (like public.vehicle_rate including all)").update();
            jdbc.sql("create temp table property_rate (like public.property_rate including all)").update();

            // A flat rate fans out over the three real seasons, with real numeric(10,2) and identity columns.
            List<RateRowResponse> created = rates.setRate("vehicle",
                    new RateWriteRequest(Map.of("vehicleTypeId", 1), new BigDecimal("71.5"), null), "smoke-test");
            assertThat(created).hasSize(3);
            assertThat(created).allSatisfy(r -> assertThat(r.rate()).isEqualByComparingTo("71.50"));

            // Closing and re-opening works, and the real partial unique index still allows it.
            LocalDate nextMonth = LocalDate.now(clock).plusMonths(1);
            rates.setRate("vehicle", new RateWriteRequest(Map.of("vehicleTypeId", 1), new BigDecimal("80"), nextMonth),
                    "smoke-test");
            assertThat(rates.list("vehicle", Map.of("vehicleTypeId", "1"), null, true, 0, 50).totalElements())
                    .isEqualTo(6);
            assertThatThrownBy(() -> rates.setRate("vehicle",
                    new RateWriteRequest(Map.of("vehicleTypeId", 1), new BigDecimal("90"), nextMonth.plusDays(5)), "smoke-test"))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.getCode()).isEqualTo("FUTURE_RATE_EXISTS"));

            // Nullable-key family against the real COALESCE(nationality_id, 0) unique index.
            Map<String, Object> keys = new java.util.HashMap<>(Map.of(
                    "propertyId", 1, "roomTypeId", 1, "seasonId", 1, "occupancy", 2));
            rates.setRate("property", new RateWriteRequest(keys, new BigDecimal("120"), null), "smoke-test");
            assertThat(rates.resolve("property", Map.of("propertyId", "1", "roomTypeId", "1", "seasonId", "1",
                    "occupancy", "2", "nationalityId", "1"), null).matched()).isEqualTo("FALLBACK_ALL_NATIONALITIES");

            // ---- the real tables are exactly as they were
            assertThat(count(jdbc, "public.vehicle_rate")).isEqualTo(realVehicleRates);
            assertThat(count(jdbc, "public.property_rate")).isEqualTo(realPropertyRates);
        }
    }

    private static long count(JdbcClient jdbc, String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }
}
