package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.AttractionResponse;
import lk.serenetravels.masterdataservice.dto.AttractionSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RateUnit;
import lk.serenetravels.masterdataservice.dto.RateWriteRequest;
import lk.serenetravels.masterdataservice.exception.ApiException;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.impl.AttractionServiceImpl;
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

class AttractionServiceTest {

    private JdbcClient jdbc;
    private RateServiceImpl rates;
    private AttractionServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Colombo"));
        rates = new RateServiceImpl(jdbc, clock);
        service = new AttractionServiceImpl(jdbc, clock, rates);
        jdbc.sql("insert into destination (name, country) values ('Sigiriya', 'Sri Lanka'), ('Yala', 'Sri Lanka'), ('Kandy', 'Sri Lanka')").update();
    }

    // ------------------------------------------------------------------ create / read

    @Test
    void createReturnsTheAttractionWithDefaults() {
        AttractionResponse created = service.create(body("name", "Sigiriya Rock", "destinationId", 1,
                "pricingMethod", "PER_PERSON"));

        assertThat(created.id()).isNotNull();
        assertThat(created.destination().name()).isEqualTo("Sigiriya");
        assertThat(created.pricingMethod()).isEqualTo("PER_PERSON");
        assertThat(created.isSafari()).isFalse();
        assertThat(created.jeepCapacity()).isNull();
        assertThat(created.currentRates()).isEmpty();
        assertThat(created.tukTuk()).isNull();
    }

    @Test
    void createReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> service.create(body("colour", "red")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("colour", "name", "destinationId", "pricingMethod"));
        assertField(() -> service.create(body("name", "X", "destinationId", 99, "pricingMethod", "PER_PERSON")), "destinationId");
        assertField(() -> service.create(body("name", "X", "destinationId", 1, "pricingMethod", "Per Person")), "pricingMethod");
        assertField(() -> service.create(body("name", "X", "destinationId", 1, "pricingMethod", "PER_PERSON", "jeepCapacity", 0)), "jeepCapacity");
        assertField(() -> service.create(body("name", "X", "destinationId", 1, "pricingMethod", "PER_PERSON", "isSafari", "yes")), "isSafari");
    }

    @Test
    void groupSizeRulesFollowThePricingMethod() {
        assertField(() -> service.create(body("name", "A", "destinationId", 1, "pricingMethod", "FIXED_GROUP_PLUS_EXTRA")),
                "fixedGroupSize");
        assertField(() -> service.create(body("name", "B", "destinationId", 1, "pricingMethod", "PER_PERSON", "fixedGroupSize", 4)),
                "fixedGroupSize");

        AttractionResponse group = service.create(body("name", "C", "destinationId", 1,
                "pricingMethod", "FIXED_GROUP_PLUS_EXTRA", "fixedGroupSize", 4));
        assertThat(group.fixedGroupSize()).isEqualTo(4);
    }

    @Test
    void aNameCanRepeatAcrossDestinationsButNotWithinOne() {
        service.create(body("name", "Boat Ride", "destinationId", 1, "pricingMethod", "PER_PERSON"));

        assertThatThrownBy(() -> service.create(body("name", "boat ride", "destinationId", 1, "pricingMethod", "PER_PERSON")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThat(service.create(body("name", "Boat Ride", "destinationId", 2, "pricingMethod", "PER_PERSON")).id()).isNotNull();
    }

    @Test
    void getAnUnknownAttractionIs404() {
        assertThatThrownBy(() -> service.get(99, null)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ update

    @Test
    void updateChangesOnlyTheGivenFieldsAndCanClearOptionalOnes() {
        int id = attraction("Yala Safari", 2, true).id();
        service.update(id, body("jeepCapacity", 6));

        AttractionResponse renamed = service.update(id, body("name", "Yala Jeep Safari"));
        assertThat(renamed.name()).isEqualTo("Yala Jeep Safari");
        assertThat(renamed.jeepCapacity()).isEqualTo(6);
        assertThat(renamed.isSafari()).isTrue();

        assertThat(service.update(id, body("jeepCapacity", null)).jeepCapacity()).isNull();
    }

    @Test
    void anUpdateIsCheckedAgainstTheRowAsItWillLook() {
        int id = attraction("Yala Safari", 2, true).id();

        // Switching method needs the group size, either in the same request or already present.
        assertField(() -> service.update(id, body("pricingMethod", "FIXED_GROUP_PLUS_EXTRA")), "fixedGroupSize");
        AttractionResponse switched = service.update(id, body("pricingMethod", "FIXED_GROUP_PLUS_EXTRA", "fixedGroupSize", 6));
        assertThat(switched.pricingMethod()).isEqualTo("FIXED_GROUP_PLUS_EXTRA");

        // ...and switching back requires dropping the group size.
        assertField(() -> service.update(id, body("pricingMethod", "PER_PERSON")), "fixedGroupSize");
        assertThat(service.update(id, body("pricingMethod", "PER_PERSON", "fixedGroupSize", null)).fixedGroupSize()).isNull();
    }

    @Test
    void updateRejectsEmptyBodiesClashesAndUnknownIds() {
        int first = attraction("Yala Safari", 2, true).id();
        attraction("Yala Park Fee", 2, false);

        assertThatThrownBy(() -> service.update(first, Map.of())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.update(first, body("name", "yala park fee")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThat(service.update(first, body("name", "Yala Safari")).name()).isEqualTo("Yala Safari"); // own name is fine
        assertThatThrownBy(() -> service.update(99, body("name", "x"))).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ list

    @Test
    void listFiltersSearchesSortsAndPages() {
        attraction("Sigiriya Rock", 1, false);
        attraction("Pidurangala", 1, false);
        attraction("Yala Safari", 2, true);
        attraction("Kandy Museum", 3, false);
        service.update(idOf("Kandy Museum"), body("pricingMethod", "FIXED_GROUP_PLUS_EXTRA", "fixedGroupSize", 5));

        assertThat(names(service.list(null, null, null, null, null, 0, 20)))
                .containsExactly("Kandy Museum", "Pidurangala", "Sigiriya Rock", "Yala Safari");
        assertThat(names(service.list("ROCK", null, null, null, null, 0, 20))).containsExactly("Sigiriya Rock");
        assertThat(names(service.list(null, 1, null, null, null, 0, 20))).containsExactly("Pidurangala", "Sigiriya Rock");
        assertThat(names(service.list(null, null, true, null, null, 0, 20))).containsExactly("Yala Safari");
        assertThat(names(service.list(null, null, null, "FIXED_GROUP_PLUS_EXTRA", null, 0, 20))).containsExactly("Kandy Museum");
        assertThat(names(service.list(null, null, null, null, List.of("destination,desc", "name,asc"), 0, 20)).get(0))
                .isEqualTo("Yala Safari");

        PageResponse<AttractionSummaryResponse> second = service.list(null, null, null, null, null, 1, 3);
        assertThat(second.totalElements()).isEqualTo(4);
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();
    }

    @Test
    void listRejectsBadSortPricingMethodAndPaging() {
        assertField(() -> service.list(null, null, null, null, List.of("secret,asc"), 0, 20), "sort");
        assertField(() -> service.list(null, null, null, "Per Person", null, 0, 20), "pricingMethod");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, 0, 500)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void listAndDetailShowThePricesInForceToday() {
        int id = attraction("Sigiriya Rock", 1, false).id();
        attraction("Pidurangala", 1, false);
        rates.setRate("attraction", rate(id, "PER_PERSON", "35", null), "t");
        rates.setRate("attraction", rate(id, "CHILD_DISCOUNT", "10", null), "t");
        rates.setRate("attraction", rate(id, "PER_PERSON", "40", "2026-12-01"), "t");   // later - not in force today

        List<AttractionSummaryResponse> rows = service.list(null, null, null, null, null, 0, 20).content();
        AttractionSummaryResponse sigiriya = rows.stream().filter(r -> r.name().equals("Sigiriya Rock")).findFirst().orElseThrow();
        AttractionSummaryResponse pidurangala = rows.stream().filter(r -> r.name().equals("Pidurangala")).findFirst().orElseThrow();

        assertThat(sigiriya.currentRates()).extracting(r -> r.rateType() + "=" + r.rate().toPlainString())
                .containsExactly("CHILD_DISCOUNT=10.00", "PER_PERSON=35.00");
        assertThat(sigiriya.currentRates()).allSatisfy(r -> assertThat(r.unit()).isEqualTo(RateUnit.USD));
        assertThat(pidurangala.currentRates()).isEmpty();

        AttractionResponse detail = service.get(id, null);
        assertThat(detail.currentRates()).hasSize(2);
        assertThat(service.get(id, LocalDate.of(2026, 12, 5)).currentRates())
                .anySatisfy(r -> assertThat(r.rate()).isEqualByComparingTo("40"));
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deleteRemovesAnUnusedAttractionButNotOneWithRates() {
        int free = attraction("Spare", 1, false).id();
        int priced = attraction("Priced", 1, false).id();
        rates.setRate("attraction", rate(priced, "PER_PERSON", "10", null), "t");

        service.delete(free);
        assertThatThrownBy(() -> service.get(free, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(priced))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("IN_USE"));
        assertThatThrownBy(() -> service.delete(99)).isInstanceOf(NotFoundException.class);
        assertThat(service.get(priced, null).name()).isEqualTo("Priced");
    }

    // ------------------------------------------------------------------ tuk-tuk

    @Test
    void tukTukOptionIsIdempotentAndLinksToFares() {
        int id = attraction("Sigiriya Rock", 1, false).id();

        int locationId = service.enableTukTuk(id).locationId();
        assertThat(service.enableTukTuk(id).locationId()).isEqualTo(locationId);   // second call changes nothing
        assertThat(service.get(id, null).tukTuk().currentFare()).isNull();

        rates.setRate("tuk-tuk", new RateWriteRequest(Map.of("tukTukLocationId", locationId), new BigDecimal("500"), null), "t");
        assertThat(service.get(id, null).tukTuk().currentFare().rate()).isEqualByComparingTo("500");
        assertThat(service.get(id, null).tukTuk().currentFare().unit()).isEqualTo(RateUnit.LKR);
    }

    @Test
    void tukTukOptionCannotBeRemovedWhileFaresExist() {
        int id = attraction("Sigiriya Rock", 1, false).id();
        int locationId = service.enableTukTuk(id).locationId();
        rates.setRate("tuk-tuk", new RateWriteRequest(Map.of("tukTukLocationId", locationId), new BigDecimal("500"), null), "t");

        assertThatThrownBy(() -> service.disableTukTuk(id))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("IN_USE"));

        int other = attraction("Pidurangala", 1, false).id();
        service.enableTukTuk(other);
        service.disableTukTuk(other);
        assertThat(service.get(other, null).tukTuk()).isNull();
        assertThatThrownBy(() -> service.enableTukTuk(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ helpers

    private AttractionResponse attraction(String name, int destinationId, boolean safari) {
        return service.create(body("name", name, "destinationId", destinationId, "pricingMethod", "PER_PERSON",
                "isSafari", safari));
    }

    private int idOf(String name) {
        return jdbc.sql("select id from attraction where name = :name").param("name", name).query(Integer.class).single();
    }

    private static List<String> names(PageResponse<AttractionSummaryResponse> page) {
        return page.content().stream().map(AttractionSummaryResponse::name).toList();
    }

    private static RateWriteRequest rate(int attractionId, String rateType, String rate, String from) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("attractionId", attractionId);
        keys.put("rateType", rateType);
        return new RateWriteRequest(keys, new BigDecimal(rate), from == null ? null : LocalDate.parse(from));
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
