package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.BabyCotRequest;
import lk.serenetravels.masterdataservice.dto.ChildRuleRequest;
import lk.serenetravels.masterdataservice.dto.DietarySupportRequest;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.PropertyInclusionRequest;
import lk.serenetravels.masterdataservice.dto.PropertyResponse;
import lk.serenetravels.masterdataservice.dto.PropertySummaryResponse;
import lk.serenetravels.masterdataservice.dto.RateUnit;
import lk.serenetravels.masterdataservice.dto.RateWriteRequest;
import lk.serenetravels.masterdataservice.dto.RoomTypeResponse;
import lk.serenetravels.masterdataservice.exception.ApiException;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.impl.PropertyServiceImpl;
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

class PropertyServiceTest {

    private JdbcClient jdbc;
    private RateServiceImpl rates;
    private PropertyServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Colombo"));
        rates = new RateServiceImpl(jdbc, clock);
        service = new PropertyServiceImpl(jdbc, clock, rates);

        jdbc.sql("insert into destination (name, country) values ('Habarana', 'Sri Lanka'), ('Kandy', 'Sri Lanka'), ('Galle', 'Sri Lanka')").update();
        jdbc.sql("insert into season (name, start_date, end_date) values ('Summer', '2026-04-01', '2026-10-31'), ('Winter', '2026-11-01', '2027-03-31')").update();
        jdbc.sql("insert into hotel_inclusion (name) values ('Breakfast'), ('Wi-Fi'), ('Airport transfer')").update();
        jdbc.sql("insert into dietary_category (name) values ('Vegetarian'), ('Halal')").update();
    }

    // ------------------------------------------------------------------ create / read

    @Test
    void createReturnsTheWholeHotelWithDefaults() {
        PropertyResponse hotel = service.create(body("code", "CIN", "name", "Cinnamon Lodge", "destinationId", 1,
                "starRating", 5, "email", "info@cinnamon.lk"));

        assertThat(hotel.id()).isNotNull();
        assertThat(hotel.code()).isEqualTo("CIN");
        assertThat(hotel.active()).isTrue();
        assertThat(hotel.starRating()).isEqualTo(5);
        assertThat(hotel.destination().name()).isEqualTo("Habarana");
        assertThat(hotel.roomTypes()).isEmpty();
        assertThat(hotel.childRules()).isEmpty();
        assertThat(hotel.inclusions()).isEmpty();
        assertThat(hotel.dietary()).isEmpty();
        assertThat(hotel.babyCot()).isNull();
        assertThat(hotel.rates()).isNull();
    }

    @Test
    void createReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> service.create(body("colour", "red")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("colour", "code", "name", "destinationId"));
        assertField(() -> service.create(body("code", "X", "name", "X", "destinationId", 99)), "destinationId");
        assertField(() -> service.create(body("code", "X", "name", "X", "destinationId", 1, "starRating", 6)), "starRating");
        assertField(() -> service.create(body("code", "X", "name", "X", "destinationId", 1, "email", "not-an-email")), "email");
        assertField(() -> service.create(body("code", "X", "name", "X", "destinationId", "abc")), "destinationId");
    }

    @Test
    void aHotelCodeMustBeUnique() {
        service.create(body("code", "CIN", "name", "Cinnamon Lodge", "destinationId", 1));

        assertThatThrownBy(() -> service.create(body("code", "CIN", "name", "Another", "destinationId", 1)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
    }

    @Test
    void getAnUnknownHotelIs404() {
        assertThatThrownBy(() -> service.get(99, false, null)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ update / deactivate

    @Test
    void updateChangesOnlyTheGivenFieldsAndCanClearOptionalOnes() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();
        service.update(id, body("address", "Habarana road", "starRating", 4));

        PropertyResponse renamed = service.update(id, body("name", "Cinnamon Lodge Habarana"));
        assertThat(renamed.name()).isEqualTo("Cinnamon Lodge Habarana");
        assertThat(renamed.address()).isEqualTo("Habarana road");
        assertThat(renamed.starRating()).isEqualTo(4);

        PropertyResponse cleared = service.update(id, body("address", null));
        assertThat(cleared.address()).isNull();
        assertThat(cleared.name()).isEqualTo("Cinnamon Lodge Habarana");
    }

    @Test
    void updateRejectsEmptyBodiesNullFlagsAndTheOtherHotelsCode() {
        int first = hotel("CIN", "Cinnamon Lodge", 1).id();
        hotel("AMA", "Amaya Hills", 2);

        assertThatThrownBy(() -> service.update(first, Map.of())).isInstanceOf(InvalidRequestException.class);
        assertField(() -> service.update(first, body("active", null)), "active");
        assertThatThrownBy(() -> service.update(first, body("code", "AMA")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        // Re-sending its own code is not a clash.
        assertThat(service.update(first, body("code", "CIN")).code()).isEqualTo("CIN");
        assertThatThrownBy(() -> service.update(99, body("name", "x"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deactivateIsASoftDeleteThatCanBeUndone() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();

        service.deactivate(id);
        assertThat(service.get(id, false, null).active()).isFalse();

        assertThat(service.update(id, body("active", true)).active()).isTrue();
        assertThatThrownBy(() -> service.deactivate(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ list

    @Test
    void listFiltersSearchesSortsAndPages() {
        hotel("CIN", "Cinnamon Lodge", 1);
        hotel("AMA", "Amaya Hills", 2);
        hotel("HER", "Heritance Kandalama", 1);
        hotel("GAL", "Galle Face Villa", 3);
        service.update(idOf("GAL"), body("starRating", 3, "active", false));
        service.update(idOf("CIN"), body("starRating", 5));
        service.update(idOf("AMA"), body("starRating", 4));

        assertThat(names(service.list(null, null, null, null, null, 0, 20))).containsExactly(
                "Amaya Hills", "Cinnamon Lodge", "Galle Face Villa", "Heritance Kandalama");
        assertThat(names(service.list("LODGE", null, null, null, null, 0, 20))).containsExactly("Cinnamon Lodge");
        assertThat(names(service.list("her", null, null, null, null, 0, 20))).containsExactly("Heritance Kandalama");
        assertThat(names(service.list(null, 1, null, null, null, 0, 20))).containsExactly("Cinnamon Lodge", "Heritance Kandalama");
        assertThat(names(service.list(null, null, false, null, null, 0, 20))).containsExactly("Galle Face Villa");
        assertThat(names(service.list(null, null, null, 4, null, 0, 20))).containsExactly("Amaya Hills");
        assertThat(names(service.list(null, null, null, null, List.of("starRating,desc"), 0, 20)).get(0)).isEqualTo("Cinnamon Lodge");
        assertThat(names(service.list(null, null, null, null, List.of("code,asc"), 0, 20)).get(0)).isEqualTo("Amaya Hills");

        PageResponse<PropertySummaryResponse> second = service.list(null, null, null, null, null, 1, 3);
        assertThat(second.totalElements()).isEqualTo(4);
        assertThat(second.totalPages()).isEqualTo(2);
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        hotel("CIN", "Cinnamon Lodge", 1);

        assertThat(service.list("%", null, null, null, null, 0, 20).content()).isEmpty();
        assertThat(service.list("_", null, null, null, null, 0, 20).content()).isEmpty();
    }

    @Test
    void listRejectsUnknownSortFieldsAndBadPaging() {
        assertField(() -> service.list(null, null, null, null, List.of("password,asc"), 0, 20), "sort");
        assertField(() -> service.list(null, null, null, null, List.of("name,sideways"), 0, 20), "sort");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, 0, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, null, null, null, -1, 20)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void listShowsRoomTypeCountAndTheCheapestRateInForceToday() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();
        hotel("AMA", "Amaya Hills", 2);
        int deluxe = service.addRoomType(id, body("name", "Deluxe")).id();
        int suite = service.addRoomType(id, body("name", "Suite")).id();
        rates.setRate("property", rate(id, deluxe, "120", null), "t");
        rates.setRate("property", rate(id, suite, "90", null), "t");
        rates.setRate("property", rate(id, suite, "60", "2026-12-01"), "t"); // starts later - must not count today

        List<PropertySummaryResponse> rows = service.list(null, null, null, null, null, 0, 20).content();
        PropertySummaryResponse cinnamon = rows.stream().filter(r -> r.code().equals("CIN")).findFirst().orElseThrow();
        PropertySummaryResponse amaya = rows.stream().filter(r -> r.code().equals("AMA")).findFirst().orElseThrow();

        assertThat(cinnamon.roomTypeCount()).isEqualTo(2);
        assertThat(cinnamon.fromRate().rate()).isEqualByComparingTo("90");
        assertThat(cinnamon.fromRate().unit()).isEqualTo(RateUnit.USD);
        assertThat(amaya.roomTypeCount()).isZero();
        assertThat(amaya.fromRate()).isNull();
    }

    @Test
    void includeRatesAddsTheRatesInForceOnTheRequestedDate() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();
        int deluxe = service.addRoomType(id, body("name", "Deluxe")).id();
        rates.setRate("property", rate(id, deluxe, "120", null), "t");
        rates.setRate("property", rate(id, deluxe, "140", "2026-12-01"), "t");

        assertThat(service.get(id, false, null).rates()).isNull();
        assertThat(service.get(id, true, null).rates()).singleElement()
                .satisfies(r -> assertThat(r.rate()).isEqualByComparingTo("120"));
        assertThat(service.get(id, true, LocalDate.of(2026, 12, 5)).rates()).singleElement()
                .satisfies(r -> assertThat(r.rate()).isEqualByComparingTo("140"));
    }

    // ------------------------------------------------------------------ room types

    @Test
    void roomTypesCanBeAddedRenamedAndDeleted() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();

        RoomTypeResponse deluxe = service.addRoomType(id, body("name", "Deluxe", "baseCapacity", 2, "extraBedAllowed", true));
        assertThat(deluxe.baseCapacity()).isEqualTo(2);
        assertThat(deluxe.extraBedAllowed()).isTrue();
        assertThat(service.addRoomType(id, body("name", "Suite")).extraBedAllowed()).isFalse();

        RoomTypeResponse renamed = service.updateRoomType(deluxe.id(), body("name", "Deluxe Garden View"));
        assertThat(renamed.name()).isEqualTo("Deluxe Garden View");
        assertThat(renamed.baseCapacity()).isEqualTo(2);

        service.deleteRoomType(deluxe.id());
        assertThat(service.get(id, false, null).roomTypes()).extracting(RoomTypeResponse::name).containsExactly("Suite");
        assertThatThrownBy(() -> service.deleteRoomType(deluxe.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void roomTypeNamesAreUniquePerHotelAndCapacityIsChecked() {
        int cinnamon = hotel("CIN", "Cinnamon Lodge", 1).id();
        int amaya = hotel("AMA", "Amaya Hills", 2).id();
        service.addRoomType(cinnamon, body("name", "Deluxe"));

        assertThatThrownBy(() -> service.addRoomType(cinnamon, body("name", "deluxe")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        service.addRoomType(amaya, body("name", "Deluxe")); // another hotel may reuse the name
        assertField(() -> service.addRoomType(cinnamon, body("name", "Big", "baseCapacity", 0)), "baseCapacity");
        assertField(() -> service.addRoomType(cinnamon, body("baseCapacity", 2)), "name");
        assertThatThrownBy(() -> service.addRoomType(99, body("name", "x"))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.updateRoomType(99, body("name", "x"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRoomTypeWithRatesCannotBeDeleted() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();
        int deluxe = service.addRoomType(id, body("name", "Deluxe")).id();
        rates.setRate("property", rate(id, deluxe, "120", null), "t");

        assertThatThrownBy(() -> service.deleteRoomType(deluxe))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("IN_USE"));
        assertThat(service.get(id, false, null).roomTypes()).hasSize(1);
    }

    // ------------------------------------------------------------------ child rules

    @Test
    void childRulesAreReplacedAsAWholeSet() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();

        service.replaceChildRules(id, List.of(child(0, 5, false, "0"), child(6, 11, true, "25.5")));
        assertThat(service.get(id, false, null).childRules()).hasSize(2)
                .extracting(r -> r.minAge() + "-" + r.maxAge()).containsExactly("0-5", "6-11");

        var replaced = service.replaceChildRules(id, List.of(child(3, 12, true, "30")));
        assertThat(replaced).singleElement().satisfies(r -> {
            assertThat(r.cost()).isEqualByComparingTo("30");
            assertThat(r.requiresBed()).isTrue();
        });
        assertThat(service.replaceChildRules(id, List.of())).isEmpty();
        assertThat(service.get(id, false, null).childRules()).isEmpty();
    }

    @Test
    void childRulesAreValidatedPerItem() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();

        assertThatThrownBy(() -> service.replaceChildRules(id, List.of(
                new ChildRuleRequest(null, 5, null, new BigDecimal("-1")),
                new ChildRuleRequest(8, 4, true, new BigDecimal("1.234")),
                new ChildRuleRequest(2, 30, true, BigDecimal.ONE))))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors()).containsKeys(
                        "[0].minAge", "[0].requiresBed", "[0].cost", "[1].maxAge", "[1].cost", "[2].maxAge"));
    }

    @Test
    void overlappingAgeBandsAreRefused() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();
        service.replaceChildRules(id, List.of(child(0, 5, false, "0")));

        assertThatThrownBy(() -> service.replaceChildRules(id, List.of(child(6, 11, true, "10"), child(0, 6, false, "0"))))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getFieldErrors()).containsKey("[0].minAge"));
        // A refused request leaves the existing rules untouched.
        assertThat(service.get(id, false, null).childRules()).singleElement()
                .satisfies(r -> assertThat(r.maxAge()).isEqualTo(5));
        assertThatThrownBy(() -> service.replaceChildRules(id, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.replaceChildRules(99, List.of())).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ inclusions, dietary, baby cot

    @Test
    void inclusionsAreReplacedAndSortedByName() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();

        var saved = service.replaceInclusions(id, List.of(
                new PropertyInclusionRequest(2, new BigDecimal("5")), new PropertyInclusionRequest(1, null)));
        assertThat(saved).extracting(i -> i.inclusion().name()).containsExactly("Breakfast", "Wi-Fi");
        assertThat(saved.get(0).cost()).isNull();
        assertThat(saved.get(1).cost()).isEqualByComparingTo("5");

        assertThat(service.replaceInclusions(id, List.of(new PropertyInclusionRequest(3, null))))
                .extracting(i -> i.inclusion().name()).containsExactly("Airport transfer");
    }

    @Test
    void inclusionsRejectUnknownDuplicateAndNegative() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();

        assertField(() -> service.replaceInclusions(id, List.of(new PropertyInclusionRequest(99, null))), "[0].inclusionId");
        assertField(() -> service.replaceInclusions(id, List.of(
                new PropertyInclusionRequest(1, null), new PropertyInclusionRequest(1, null))), "[1].inclusionId");
        assertField(() -> service.replaceInclusions(id, List.of(new PropertyInclusionRequest(1, new BigDecimal("-2")))), "[0].cost");
        assertField(() -> service.replaceInclusions(id, List.of(new PropertyInclusionRequest(null, null))), "[0].inclusionId");
    }

    @Test
    void dietarySupportIsReplacedAndValidated() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();

        var saved = service.replaceDietary(id, List.of(new DietarySupportRequest(2, false), new DietarySupportRequest(1, true)));
        assertThat(saved).extracting(d -> d.category().name() + "=" + d.supported())
                .containsExactly("Vegetarian=true", "Halal=false");

        assertField(() -> service.replaceDietary(id, List.of(new DietarySupportRequest(99, true))), "[0].dietaryCategoryId");
        assertField(() -> service.replaceDietary(id, List.of(new DietarySupportRequest(1, null))), "[0].supported");
        assertField(() -> service.replaceDietary(id, List.of(
                new DietarySupportRequest(1, true), new DietarySupportRequest(1, false))), "[1].dietaryCategoryId");
        assertThat(service.get(id, false, null).dietary()).hasSize(2); // refused requests changed nothing
    }

    @Test
    void babyCotIsCreatedThenUpdatedAndClearsItsPriceWhenUnavailable() {
        int id = hotel("CIN", "Cinnamon Lodge", 1).id();

        assertThat(service.setBabyCot(id, new BabyCotRequest(true, new BigDecimal("10")))).satisfies(c -> {
            assertThat(c.available()).isTrue();
            assertThat(c.cost()).isEqualByComparingTo("10");
        });
        assertThat(service.get(id, false, null).babyCot().cost()).isEqualByComparingTo("10");

        assertThat(service.setBabyCot(id, new BabyCotRequest(false, new BigDecimal("10")))).satisfies(c -> {
            assertThat(c.available()).isFalse();
            assertThat(c.cost()).isNull();
        });
        assertThat(jdbc.sql("select count(*) from baby_cot_option where property_id = :id").param("id", id)
                .query(Long.class).single()).isEqualTo(1L);

        assertField(() -> service.setBabyCot(id, new BabyCotRequest(null, null)), "available");
        assertField(() -> service.setBabyCot(id, new BabyCotRequest(true, new BigDecimal("-1"))), "cost");
        assertThatThrownBy(() -> service.setBabyCot(99, new BabyCotRequest(true, null))).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ helpers

    private PropertyResponse hotel(String code, String name, int destinationId) {
        return service.create(body("code", code, "name", name, "destinationId", destinationId));
    }

    private int idOf(String code) {
        return jdbc.sql("select id from property where code = :code").param("code", code).query(Integer.class).single();
    }

    private static List<String> names(PageResponse<PropertySummaryResponse> page) {
        return page.content().stream().map(PropertySummaryResponse::name).toList();
    }

    private static RateWriteRequest rate(int propertyId, int roomTypeId, String rate, String from) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("propertyId", propertyId);
        keys.put("roomTypeId", roomTypeId);
        keys.put("seasonId", 1);
        keys.put("occupancy", 2);
        return new RateWriteRequest(keys, new BigDecimal(rate), from == null ? null : LocalDate.parse(from));
    }

    private static ChildRuleRequest child(int min, int max, boolean bed, String cost) {
        return new ChildRuleRequest(min, max, bed, new BigDecimal(cost));
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
