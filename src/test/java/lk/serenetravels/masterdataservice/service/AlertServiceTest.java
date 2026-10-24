package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.AlertResponse;
import lk.serenetravels.masterdataservice.dto.AlertSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RateCorrectionRequest;
import lk.serenetravels.masterdataservice.dto.RateWriteRequest;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.service.impl.AlertServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.RateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertServiceTest {

    private JdbcClient jdbc;
    private RateServiceImpl rates;
    private AlertServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Colombo"));   // today = 2026-10-18
        rates = new RateServiceImpl(jdbc, clock);
        service = new AlertServiceImpl(jdbc, clock, rates);
        jdbc.sql("insert into destination (name, country) values ('Sigiriya', 'Sri Lanka')").update();
        jdbc.sql("insert into vehicle_type (name, min_capacity, max_capacity) values ('Van', 1, 9)").update();
        jdbc.sql("insert into driver (name, nic, role, active) values ('Kamal', '111V', 'DRIVER', true), "
                + "('Nimal', '222V', 'DRIVER', false), ('Sunil', '333V', 'ASSISTANT', false), ('Ruwan', '444V', 'ASSISTANT', true)").update();
    }

    // ------------------------------------------------------------------ vehicles

    @Test
    void vehicleDocumentsAreGradedByHowCloseTheyAre() {
        vehicle("EXPIRED", "2026-10-08", "2026-10-18", 1);        // insurance 10 days ago, licence today
        vehicle("SOON", "2026-10-25", "2026-11-07", 1);           // 7 days, 20 days
        vehicle("FAR", "2027-01-01", "2026-12-31", 1);            // outside 30 days
        vehicle("UNKNOWN", null, null, 1);                        // no dates recorded: nothing to say

        List<AlertResponse> alerts = service.list(null, null, null, 0, 50).content();

        assertThat(alerts).extracting(a -> a.code() + " " + a.subject().name() + " " + a.severity() + " " + a.daysLeft())
                .containsExactly(
                        "VEHICLE_INSURANCE_EXPIRING EXPIRED CRITICAL -10",
                        "VEHICLE_LICENSE_EXPIRING EXPIRED WARNING 0",             // expires today: not yet expired, so WARNING
                        "VEHICLE_INSURANCE_EXPIRING SOON WARNING 7",
                        "VEHICLE_LICENSE_EXPIRING SOON INFO 20");
        AlertResponse expired = alerts.get(0);
        assertThat(expired.subjectType()).isEqualTo("vehicle");
        assertThat(expired.dueDate()).isEqualTo("2026-10-08");
        assertThat(expired.message()).isEqualTo("Insurance of EXPIRED expired 10 days ago (2026-10-08)");
        assertThat(alerts.get(1).message()).isEqualTo("Revenue licence of EXPIRED expires today (2026-10-18)");
        assertThat(alerts.get(3).message()).isEqualTo("Revenue licence of SOON expires in 20 days (2026-11-07)");
    }

    @Test
    void theLookAheadWindowIsConfigurableAndExpiredAlwaysCounts() {
        vehicle("FAR", "2027-01-01", "2026-10-01", 1);           // insurance in 75 days, licence already expired

        assertThat(service.list(null, null, null, 0, 50).content()).extracting(AlertResponse::code)
                .containsExactly("VEHICLE_LICENSE_EXPIRING");
        assertThat(service.list(0, null, null, 0, 50).content()).extracting(AlertResponse::code)
                .containsExactly("VEHICLE_LICENSE_EXPIRING");                                  // 0 days: only what is already due
        assertThat(service.list(90, null, null, 0, 50).content()).extracting(AlertResponse::code)
                .containsExactly("VEHICLE_LICENSE_EXPIRING", "VEHICLE_INSURANCE_EXPIRING");
    }

    @Test
    void anOutOfServiceVehicleNeedsNoAttention() {
        int id = vehicle("OLD", "2020-01-01", "2020-01-01", null);
        jdbc.sql("update vehicle set active = false where id = :id").param("id", id).update();

        assertThat(service.list(null, null, null, 0, 50).content()).isEmpty();
    }

    @Test
    void driverProblemsAreReportedWithTheirOwnCodes() {
        vehicle("NODRV", "2027-06-01", "2027-06-01", null);                     // no driver
        vehicle("BADDRV", "2027-06-01", "2027-06-01", 2);                       // inactive driver
        int fine = vehicle("FINE", "2027-06-01", "2027-06-01", 1);              // active driver
        jdbc.sql("update vehicle set assistant_id = 3 where id = :id").param("id", fine).update();   // inactive assistant

        List<AlertResponse> alerts = service.list(null, null, null, 0, 50).content();

        assertThat(alerts).extracting(a -> a.code() + " " + a.subject().name() + " " + a.severity())
                .containsExactly("VEHICLE_DRIVER_INACTIVE BADDRV WARNING", "VEHICLE_NO_DRIVER NODRV WARNING",
                        "VEHICLE_ASSISTANT_INACTIVE FINE INFO");
        assertThat(alerts.get(0).message()).contains("Nimal");
        assertThat(alerts).allSatisfy(a -> assertThat(a.daysLeft()).isNull());
    }

    // ------------------------------------------------------------------ rates ending

    @Test
    void aRateThatRunsOutWithNothingAfterItIsReported() {
        int attraction = attraction("Sigiriya Rock");
        int rate = rates.setRate("attraction", new RateWriteRequest(
                Map.of("attractionId", attraction, "rateType", "PER_PERSON"), new BigDecimal("35"), null), "t").get(0).id();
        rates.correct("attraction", rate, new RateCorrectionRequest(null, null, LocalDate.parse("2026-10-25")));

        List<AlertResponse> alerts = service.list(null, "rate_ending_soon", null, 0, 50).content();

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.subjectType()).isEqualTo("rate:attraction");
            assertThat(a.severity()).isEqualTo("WARNING");        // 7 days
            assertThat(a.daysLeft()).isEqualTo(7);
            assertThat(a.dueDate()).isEqualTo("2026-10-25");
            assertThat(a.message()).isEqualTo("Attraction rates (Sigiriya Rock, PER_PERSON) ends in 7 days (2026-10-25) and no later rate is set");
        });
    }

    @Test
    void aRateWithASuccessorLinedUpIsNotReported() {
        int attraction = attraction("Sigiriya Rock");
        rates.setRate("attraction", new RateWriteRequest(
                Map.of("attractionId", attraction, "rateType", "PER_PERSON"), new BigDecimal("35"), null), "t");
        // the new price starts in 10 days, which closes the old one then - but a price follows it
        rates.setRate("attraction", new RateWriteRequest(
                Map.of("attractionId", attraction, "rateType", "PER_PERSON"), new BigDecimal("40"), LocalDate.parse("2026-10-28")), "t");

        assertThat(service.list(null, "RATE_ENDING_SOON", null, 0, 50).content()).isEmpty();
    }

    @Test
    void aSuccessorOnlyCountsForTheSameKeys() {
        int attraction = attraction("Sigiriya Rock");
        int adult = rates.setRate("attraction", new RateWriteRequest(
                Map.of("attractionId", attraction, "rateType", "PER_PERSON"), new BigDecimal("35"), null), "t").get(0).id();
        rates.correct("attraction", adult, new RateCorrectionRequest(null, null, LocalDate.parse("2026-10-25")));
        // a rate of ANOTHER type starting later does not take over from the adult price
        rates.setRate("attraction", new RateWriteRequest(
                Map.of("attractionId", attraction, "rateType", "CHILD_DISCOUNT"), new BigDecimal("10"), LocalDate.parse("2026-10-25")), "t");

        assertThat(service.list(null, "RATE_ENDING_SOON", null, 0, 50).content()).hasSize(1);
    }

    @Test
    void anAppliesToAllRateIsMatchedToItsOwnSuccessor() {
        // guide accommodation has a nullable destination ("all destinations"): NULL must match NULL
        jdbc.sql("insert into season (name, start_date, end_date) values ('Peak', '2026-12-01', '2027-03-31')").update();
        int row = rates.setRate("guide-accommodation", new RateWriteRequest(
                Map.of("seasonId", 1), new BigDecimal("4500"), null), "t").get(0).id();
        rates.correct("guide-accommodation", row, new RateCorrectionRequest(null, null, LocalDate.parse("2026-10-30")));
        assertThat(service.list(null, "RATE_ENDING_SOON", null, 0, 50).content()).hasSize(1);

        rates.setRate("guide-accommodation", new RateWriteRequest(
                Map.of("seasonId", 1), new BigDecimal("5000"), LocalDate.parse("2026-10-30")), "t");
        assertThat(service.list(null, "RATE_ENDING_SOON", null, 0, 50).content()).isEmpty();
    }

    // ------------------------------------------------------------------ no current rate

    @Test
    void anActivePropertyOrAttractionWithoutARateInForceIsReported() {
        int priced = property("Priced Hotel", true);
        int lapsed = property("Lapsed Hotel", true);
        int brandNew = property("New Hotel", true);
        property("Closed Hotel", false);
        int room = jdbc.sql("insert into room_type (property_id, name) values (:p, 'Deluxe')").param("p", priced).update();
        jdbc.sql("insert into room_type (property_id, name) values (:p, 'Deluxe')").param("p", lapsed).update();
        jdbc.sql("insert into season (name, start_date, end_date) values ('Peak', '2026-12-01', '2027-03-31')").update();
        propertyRate(priced, 1, "2026-01-01", null);                  // open-ended: in force
        propertyRate(lapsed, 2, "2025-01-01", "2026-06-01");          // ended 139 days ago

        int priceless = attraction("Priceless Rock");
        int pricedAttraction = attraction("Priced Rock");
        rates.setRate("attraction", new RateWriteRequest(
                Map.of("attractionId", pricedAttraction, "rateType", "PER_PERSON"), new BigDecimal("35"), null), "t");

        List<AlertResponse> alerts = service.list(null, null, null, 0, 50).content();

        assertThat(alerts).extracting(a -> a.code() + " " + a.subject().name())
                .containsExactlyInAnyOrder("PROPERTY_NO_CURRENT_RATE Lapsed Hotel", "PROPERTY_NO_CURRENT_RATE New Hotel",
                        "ATTRACTION_NO_CURRENT_RATE Priceless Rock");                       // not Priced, not the closed hotel
        AlertResponse lapsedAlert = alerts.stream().filter(a -> a.subject().name().equals("Lapsed Hotel")).findFirst().orElseThrow();
        assertThat(lapsedAlert.severity()).isEqualTo("WARNING");
        assertThat(lapsedAlert.dueDate()).isEqualTo("2026-06-01");
        assertThat(lapsedAlert.daysLeft()).isEqualTo(-139);
        assertThat(lapsedAlert.message()).isEqualTo("Lapsed Hotel has no room rate in force today (the last one ended on 2026-06-01)");
        AlertResponse newAlert = alerts.stream().filter(a -> a.subject().name().equals("New Hotel")).findFirst().orElseThrow();
        assertThat(newAlert.dueDate()).isNull();
        assertThat(newAlert.message()).isEqualTo("New Hotel has no room rate in force today");
        assertThat(brandNew).isNotEqualTo(priceless);
    }

    // ------------------------------------------------------------------ ordering, filters, summary

    @Test
    void mostUrgentComesFirst() {
        vehicle("B-INFO", "2026-11-05", "2027-06-01", 1);         // 18 days: INFO
        vehicle("A-CRIT", "2026-09-01", "2027-06-01", 1);         // expired: CRITICAL
        vehicle("C-WARN", "2026-10-20", "2027-06-01", 1);         // 2 days: WARNING
        vehicle("D-NODRV", "2027-06-01", "2027-06-01", null);     // WARNING, no date

        assertThat(service.list(null, null, null, 0, 50).content()).extracting(a -> a.severity() + " " + a.subject().name())
                .containsExactly("CRITICAL A-CRIT", "WARNING C-WARN", "WARNING D-NODRV", "INFO B-INFO");
    }

    @Test
    void filtersPagingAndSummaryAgree() {
        vehicle("A", "2026-09-01", "2026-09-01", null);           // 2 expired (CRITICAL) + no driver (WARNING)
        vehicle("B", "2026-10-20", "2027-06-01", 1);              // WARNING
        property("Hotel", true);                                  // WARNING

        PageResponse<AlertResponse> all = service.list(null, null, null, 0, 50);
        assertThat(all.totalElements()).isEqualTo(5);
        assertThat(service.list(null, "VEHICLE_NO_DRIVER", null, 0, 50).totalElements()).isEqualTo(1);
        assertThat(service.list(null, null, "critical", 0, 50).totalElements()).isEqualTo(2);
        assertThat(service.list(null, "VEHICLE_NO_DRIVER", "CRITICAL", 0, 50).content()).isEmpty();

        PageResponse<AlertResponse> second = service.list(null, null, null, 1, 2);
        assertThat(second.content()).hasSize(2);
        assertThat(second.totalElements()).isEqualTo(5);
        assertThat(service.list(null, null, null, 2, 2).last()).isTrue();
        assertThat(service.list(null, null, null, 9, 2).content()).isEmpty();                 // past the end: empty, not an error

        AlertSummaryResponse summary = service.summary(null);
        assertThat(summary.total()).isEqualTo(5);
        assertThat(summary.bySeverity()).containsEntry("CRITICAL", 2).containsEntry("WARNING", 3).containsEntry("INFO", 0);
        assertThat(summary.byCode()).containsEntry("VEHICLE_INSURANCE_EXPIRING", 2).containsEntry("VEHICLE_LICENSE_EXPIRING", 1)
                .containsEntry("VEHICLE_NO_DRIVER", 1).containsEntry("PROPERTY_NO_CURRENT_RATE", 1).containsEntry("RATE_ENDING_SOON", 0)
                .hasSize(8);
    }

    @Test
    void anEmptySystemHasNoAlertsAndAFullySetOutSummary() {
        assertThat(service.list(null, null, null, 0, 50).content()).isEmpty();
        AlertSummaryResponse summary = service.summary(null);
        assertThat(summary.total()).isZero();
        assertThat(summary.bySeverity()).containsOnlyKeys("CRITICAL", "WARNING", "INFO");
        assertThat(summary.byCode()).hasSize(8).allSatisfy((code, count) -> assertThat(count).isZero());
    }

    @Test
    void badFiltersAreRejectedTogether() {
        assertThatThrownBy(() -> service.list(-1, "GOSSIP", "LOUD", 0, 50)).isInstanceOfSatisfying(InvalidRequestException.class,
                e -> assertThat(e.getFieldErrors()).containsKeys("withinDays", "code", "severity"));
        assertThatThrownBy(() -> service.list(4000, null, null, 0, 50)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.summary(-5)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, null, 0, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, null, -1, 50)).isInstanceOf(InvalidRequestException.class);
    }

    // ------------------------------------------------------------------ helpers

    private int vehicle(String registration, String insurance, String license, Integer driverId) {
        jdbc.sql("""
                        insert into vehicle (registration_no, vehicle_type_id, seating_capacity, method, insurance_expiry_date,
                                             revenue_license_expiry_date, driver_id)
                        values (:reg, 1, 7, 'INTERNAL', :ins, :lic, :driver)""")
                .param("reg", registration).param("ins", insurance == null ? null : LocalDate.parse(insurance), java.sql.Types.DATE)
                .param("lic", license == null ? null : LocalDate.parse(license), java.sql.Types.DATE)
                .param("driver", driverId, java.sql.Types.INTEGER).update();
        return jdbc.sql("select id from vehicle where registration_no = :reg").param("reg", registration).query(Integer.class).single();
    }

    private int attraction(String name) {
        jdbc.sql("insert into attraction (destination_id, name, pricing_method) values (1, :n, 'PER_PERSON')").param("n", name).update();
        return jdbc.sql("select id from attraction where name = :n").param("n", name).query(Integer.class).single();
    }

    private int property(String name, boolean active) {
        jdbc.sql("insert into property (destination_id, name, code, active) values (1, :n, :c, :a)")
                .param("n", name).param("c", name.toUpperCase().replace(' ', '-')).param("a", active).update();
        return jdbc.sql("select id from property where name = :n").param("n", name).query(Integer.class).single();
    }

    private void propertyRate(int propertyId, int roomTypeId, String from, String to) {
        jdbc.sql("""
                        insert into property_rate (property_id, room_type_id, season_id, rate, effective_from, effective_to,
                                                   created_by, occupancy)
                        values (:p, :r, 1, 100, :from, :to, 'test', 2)""")
                .param("p", propertyId).param("r", roomTypeId).param("from", LocalDate.parse(from))
                .param("to", to == null ? null : LocalDate.parse(to), java.sql.Types.DATE).update();
    }
}
