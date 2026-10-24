package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.PromotionResponse;
import lk.serenetravels.masterdataservice.dto.RateAdjustItem;
import lk.serenetravels.masterdataservice.dto.RateAdjustRequest;
import lk.serenetravels.masterdataservice.dto.RateCorrectionRequest;
import lk.serenetravels.masterdataservice.dto.RateRowResponse;
import lk.serenetravels.masterdataservice.dto.RateUnit;
import lk.serenetravels.masterdataservice.dto.RateWriteRequest;
import lk.serenetravels.masterdataservice.exception.ApiException;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.impl.PromotionServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.RateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionServiceTest {

    private JdbcClient jdbc;
    private RateServiceImpl rates;
    private PromotionServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Colombo"));   // today = 2026-10-18
        rates = new RateServiceImpl(jdbc, clock);
        service = new PromotionServiceImpl(jdbc, clock);
        jdbc.sql("insert into destination (name, country) values ('Sigiriya', 'Sri Lanka'), ('Kandy', 'Sri Lanka')").update();
        jdbc.sql("""
                insert into season (name, start_date, end_date) values
                ('Peak', '2026-12-01', '2027-03-31'), ('Low', '2027-05-01', '2027-09-30')""").update();
    }

    // ------------------------------------------------------------------ create / read

    @Test
    void createReturnsThePromotionRunningWithNoValueYet() {
        PromotionResponse created = service.create(body("name", "Early Bird", "type", "PERCENTAGE",
                "validFrom", "2026-10-01", "validTo", "2026-12-01", "minGroupSize", 4, "destinationId", 1, "seasonId", 2));

        assertThat(created.id()).isNotNull();
        assertThat(created.type()).isEqualTo("PERCENTAGE");
        assertThat(created.validFrom()).isEqualTo("2026-10-01");
        assertThat(created.minGroupSize()).isEqualTo(4);
        assertThat(created.destination().name()).isEqualTo("Sigiriya");
        assertThat(created.season().name()).isEqualTo("Low");
        assertThat(created.active()).isTrue();
        assertThat(created.status()).isEqualTo("RUNNING");
        assertThat(created.currentValue()).isNull();
    }

    @Test
    void theLimitsAreOptionalAndTheTypeIsUpperCased() {
        PromotionResponse created = promo("Open To All", "fixed_amount", "2026-10-01", "2026-12-01");

        assertThat(created.type()).isEqualTo("FIXED_AMOUNT");
        assertThat(created.minGroupSize()).isNull();
        assertThat(created.destination()).isNull();
        assertThat(created.season()).isNull();
    }

    @Test
    void createReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> service.create(body("colour", "red")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("colour", "name", "type", "validFrom", "validTo"));
        assertThatThrownBy(() -> service.create(body("name", "X", "type", "BOGO", "validFrom", "2026-10-01", "validTo", "2026-10-01",
                "minGroupSize", 0, "destinationId", 99, "seasonId", 99)))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("type", "validTo", "minGroupSize", "destinationId", "seasonId"));
        assertField(() -> service.create(body("name", "X", "type", "PERCENTAGE", "validFrom", "1 Oct", "validTo", "2026-12-01")), "validFrom");
        assertField(() -> service.create(body("name", "X", "type", "PERCENTAGE", "validFrom", "2026-12-01", "validTo", "2026-10-01")), "validTo");
        assertField(() -> service.create(body("name", "X", "type", "PERCENTAGE", "validFrom", "2026-10-01", "validTo", "2026-12-01",
                "minGroupSize", 40000)), "minGroupSize");
        assertThat(jdbc.sql("select count(*) from promotion").query(Long.class).single()).isZero();   // nothing half-saved
    }

    @Test
    void getAnUnknownPromotionIs404() {
        assertThatThrownBy(() -> service.get(99, null)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ status and value

    @Test
    void statusFollowsTheWindowAndTheWindowIsHalfOpen() {
        int id = promo("Window", "PERCENTAGE", "2026-11-01", "2026-12-01").id();

        assertThat(service.get(id, LocalDate.parse("2026-10-31")).status()).isEqualTo("UPCOMING");
        assertThat(service.get(id, LocalDate.parse("2026-11-01")).status()).isEqualTo("RUNNING");
        assertThat(service.get(id, LocalDate.parse("2026-11-30")).status()).isEqualTo("RUNNING");
        assertThat(service.get(id, LocalDate.parse("2026-12-01")).status()).isEqualTo("ENDED");      // validTo is not included
        assertThat(service.get(id, null).status()).isEqualTo("UPCOMING");                            // today is 2026-10-18

        service.deactivate(id);
        assertThat(service.get(id, LocalDate.parse("2026-11-15")).status()).isEqualTo("INACTIVE");
    }

    @Test
    void theValueShownIsTheOneInForceAndItsUnitFollowsTheType() {
        int percent = promo("Ten Percent", "PERCENTAGE", "2026-10-01", "2026-12-01").id();
        int fixed = promo("Fifty Off", "FIXED_AMOUNT", "2026-10-01", "2026-12-01").id();
        int free = promo("Free Night", "COMPLIMENTARY", "2026-10-01", "2026-12-01").id();
        rates.setRate("promotion", rate(percent, "10", null), "t");
        rates.setRate("promotion", rate(fixed, "50", null), "t");
        rates.setRate("promotion", rate(free, "1", null), "t");
        rates.setRate("promotion", rate(percent, "15", "2026-11-01"), "t");

        assertThat(service.get(percent, null).currentValue().rate()).isEqualByComparingTo("10");
        assertThat(service.get(percent, null).currentValue().unit()).isEqualTo(RateUnit.PERCENT);
        assertThat(service.get(percent, LocalDate.parse("2026-11-01")).currentValue().rate()).isEqualByComparingTo("15");
        assertThat(service.get(percent, LocalDate.parse("2026-01-01")).currentValue()).isNull();
        assertThat(service.get(fixed, null).currentValue().unit()).isEqualTo(RateUnit.USD);
        assertThat(service.get(free, null).currentValue().unit()).isEqualTo(RateUnit.USD);
    }

    @Test
    void theRateEngineReportsAndCapsPromotionValuesByType() {
        int percent = promo("Ten Percent", "PERCENTAGE", "2026-10-01", "2026-12-01").id();
        int fixed = promo("Fifty Off", "FIXED_AMOUNT", "2026-10-01", "2026-12-01").id();

        // rows and the resolved rate carry the promotion's own unit
        RateRowResponse row = rates.setRate("promotion", rate(percent, "10", null), "t").get(0);
        assertThat(row.unit()).isEqualTo(RateUnit.PERCENT);
        assertThat(rates.setRate("promotion", rate(fixed, "50", null), "t").get(0).unit()).isEqualTo(RateUnit.USD);
        assertThat(rates.resolve("promotion", Map.of("promotionId", String.valueOf(percent)), null).unit()).isEqualTo(RateUnit.PERCENT);
        assertThat(rates.listCurrent("promotion", Map.of(), null)).extracting(RateRowResponse::unit)
                .containsExactlyInAnyOrder(RateUnit.PERCENT, RateUnit.USD);

        // a percentage is capped at 100; an amount of money is not
        assertThatThrownBy(() -> rates.setRate("promotion", rate(percent, "150", "2026-11-01"), "t"))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors()).containsKey("rate"));
        assertThat(rates.setRate("promotion", rate(fixed, "150", "2026-11-01"), "t")).hasSize(1);
        assertThatThrownBy(() -> rates.correct("promotion", row.id(), new RateCorrectionRequest(new BigDecimal("101"), null, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(rates.correct("promotion", row.id(), new RateCorrectionRequest(new BigDecimal("100"), null, null)).rate())
                .isEqualByComparingTo("100");

        // a bulk adjustment that would push a percentage past 100 is refused, in a dry run too
        assertThatThrownBy(() -> rates.adjust("promotion", new RateAdjustRequest(Map.of("promotionId", percent),
                RateAdjustRequest.Mode.PERCENT, new BigDecimal("10"), null, null, true), "t"))
                .isInstanceOf(InvalidRequestException.class);
        List<RateAdjustItem> fixedItems = rates.adjust("promotion", new RateAdjustRequest(Map.of("promotionId", fixed),
                RateAdjustRequest.Mode.PERCENT, new BigDecimal("10"), null, null, true), "t");
        assertThat(fixedItems).singleElement().satisfies(i -> assertThat(i.unit()).isEqualTo(RateUnit.USD));
    }

    // ------------------------------------------------------------------ update / deactivate

    @Test
    void updateChangesOnlyWhatWasSent() {
        int id = service.create(body("name", "Early Bird", "type", "PERCENTAGE", "validFrom", "2026-10-01",
                "validTo", "2026-12-01", "minGroupSize", 4, "destinationId", 1)).id();

        PromotionResponse renamed = service.update(id, body("name", "Early Bird 2026"));
        assertThat(renamed.name()).isEqualTo("Early Bird 2026");
        assertThat(renamed.destination().name()).isEqualTo("Sigiriya");
        assertThat(renamed.minGroupSize()).isEqualTo(4);

        PromotionResponse cleared = service.update(id, body("destinationId", null, "minGroupSize", null, "seasonId", 1));
        assertThat(cleared.destination()).isNull();
        assertThat(cleared.minGroupSize()).isNull();
        assertThat(cleared.season().name()).isEqualTo("Peak");
    }

    @Test
    void updateChecksTheDatesAsTheyWillBeAndRejectsBadInput() {
        int id = promo("Early Bird", "PERCENTAGE", "2026-10-01", "2026-12-01").id();

        assertThatThrownBy(() -> service.update(id, Map.of())).isInstanceOf(InvalidRequestException.class);
        assertField(() -> service.update(id, body("validTo", "2026-09-01")), "validTo");      // before the stored validFrom
        assertField(() -> service.update(id, body("validFrom", "2026-12-01")), "validTo");    // equal to the stored validTo
        assertField(() -> service.update(id, body("type", "BOGO")), "type");
        assertField(() -> service.update(id, body("name", " ")), "name");
        assertField(() -> service.update(id, body("seasonId", 99)), "seasonId");
        assertField(() -> service.update(id, body("active", "maybe")), "active");
        assertField(() -> service.update(id, body("colour", "red")), "colour");
        assertThat(service.update(id, body("validFrom", "2026-09-01", "validTo", "2027-01-01")).validTo()).isEqualTo("2027-01-01");
        assertThatThrownBy(() -> service.update(99, body("name", "x"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theTypeIsLockedOnceAValueExists() {
        int id = promo("Early Bird", "PERCENTAGE", "2026-10-01", "2026-12-01").id();

        assertThat(service.update(id, body("type", "fixed_amount")).type()).isEqualTo("FIXED_AMOUNT");   // free while unpriced
        rates.setRate("promotion", rate(id, "50", null), "t");

        assertThatThrownBy(() -> service.update(id, body("type", "PERCENTAGE")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("TYPE_LOCKED"));
        assertThat(service.update(id, body("type", "FIXED_AMOUNT", "name", "Fifty Off")).name()).isEqualTo("Fifty Off");   // not a change
    }

    @Test
    void deactivateKeepsThePromotionAndItCanComeBack() {
        int id = promo("Early Bird", "PERCENTAGE", "2026-10-01", "2026-12-01").id();
        rates.setRate("promotion", rate(id, "10", null), "t");

        service.deactivate(id);
        service.deactivate(id);                                                       // already inactive: still fine
        assertThat(service.get(id, null).active()).isFalse();
        assertThat(service.get(id, null).currentValue().rate()).isEqualByComparingTo("10");   // history kept
        assertThat(service.update(id, body("active", true)).active()).isTrue();
        assertThatThrownBy(() -> service.deactivate(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ list

    @Test
    void listFiltersSearchesSortsAndPages() {
        int early = service.create(body("name", "Early Bird", "type", "PERCENTAGE", "validFrom", "2026-10-01",
                "validTo", "2026-12-01", "destinationId", 1)).id();
        service.create(body("name", "Peak Offer", "type", "FIXED_AMOUNT", "validFrom", "2026-12-01",
                "validTo", "2027-04-01", "seasonId", 1));
        int old = promo("Old Deal", "COMPLIMENTARY", "2025-01-01", "2025-06-01").id();
        service.deactivate(old);
        rates.setRate("promotion", rate(early, "10", null), "t");

        assertThat(names(service.list(null, null, null, null, null, null, null, null, 0, 20)))
                .containsExactly("Peak Offer", "Early Bird", "Old Deal");                       // newest first
        assertThat(names(service.list("BIRD", null, null, null, null, null, null, null, 0, 20))).containsExactly("Early Bird");
        assertThat(names(service.list(null, "fixed_amount", null, null, null, null, null, null, 0, 20))).containsExactly("Peak Offer");
        assertThat(names(service.list(null, null, false, null, null, null, null, null, 0, 20))).containsExactly("Old Deal");
        assertThat(names(service.list(null, null, true, null, null, null, null, null, 0, 20))).containsExactly("Peak Offer", "Early Bird");
        assertThat(names(service.list(null, null, null, null, 1, null, null, null, 0, 20))).containsExactly("Early Bird");
        assertThat(names(service.list(null, null, null, null, null, 1, null, null, 0, 20))).containsExactly("Peak Offer");
        assertThat(names(service.list(null, null, null, LocalDate.parse("2026-11-30"), null, null, null, null, 0, 20))).containsExactly("Early Bird");
        assertThat(names(service.list(null, null, null, LocalDate.parse("2026-12-01"), null, null, null, null, 0, 20))).containsExactly("Peak Offer");   // half-open
        assertThat(names(service.list(null, null, null, null, null, null, null, List.of("name,asc"), 0, 20)))
                .containsExactly("Early Bird", "Old Deal", "Peak Offer");

        PageResponse<PromotionResponse> second = service.list(null, null, null, null, null, null, null, null, 1, 2);
        assertThat(second.totalElements()).isEqualTo(3);
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();
        // every row carries its status and value
        PromotionResponse first = service.list("early", null, null, null, null, null, null, null, 0, 20).content().get(0);
        assertThat(first.status()).isEqualTo("RUNNING");
        assertThat(first.currentValue().unit()).isEqualTo(RateUnit.PERCENT);
        assertThat(service.list(null, null, null, null, null, null, LocalDate.parse("2027-01-01"), List.of("name,asc"), 0, 20)
                .content()).extracting(PromotionResponse::status).containsExactly("ENDED", "INACTIVE", "RUNNING");
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        promo("Early Bird", "PERCENTAGE", "2026-10-01", "2026-12-01");

        assertThat(service.list("%", null, null, null, null, null, null, null, 0, 20).content()).isEmpty();
        assertThat(service.list("_", null, null, null, null, null, null, null, 0, 20).content()).isEmpty();
    }

    @Test
    void listRejectsBadTypeSortAndPaging() {
        assertField(() -> service.list(null, "BOGO", null, null, null, null, null, null, 0, 20), "type");
        assertField(() -> service.list(null, null, null, null, null, null, null, List.of("password,asc"), 0, 20), "sort");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, null, null, 0, 0)).isInstanceOf(InvalidRequestException.class);
    }

    // ------------------------------------------------------------------ helpers

    private PromotionResponse promo(String name, String type, String from, String to) {
        return service.create(body("name", name, "type", type, "validFrom", from, "validTo", to));
    }

    private static RateWriteRequest rate(int promotionId, String amount, String from) {
        return new RateWriteRequest(Map.of("promotionId", promotionId), new BigDecimal(amount), from == null ? null : LocalDate.parse(from));
    }

    private static List<String> names(PageResponse<PromotionResponse> page) {
        return page.content().stream().map(PromotionResponse::name).toList();
    }

    // Builds a JSON-style body from key/value pairs; unlike Map.of it allows null values (meaning "clear").
    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static void assertField(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getFieldErrors()).containsKey(field));
    }
}
