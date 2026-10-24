package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RateAdjustItem;
import lk.serenetravels.masterdataservice.dto.RateAdjustRequest;
import lk.serenetravels.masterdataservice.dto.RateCorrectionRequest;
import lk.serenetravels.masterdataservice.dto.RateFamilyResponse;
import lk.serenetravels.masterdataservice.dto.RateRowResponse;
import lk.serenetravels.masterdataservice.dto.RateUnit;
import lk.serenetravels.masterdataservice.dto.RateWriteRequest;
import lk.serenetravels.masterdataservice.dto.ResolvedRateResponse;
import lk.serenetravels.masterdataservice.exception.ApiException;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
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

class RateServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    private JdbcClient jdbc;
    private RateServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        service = new RateServiceImpl(jdbc, Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Colombo")));

        jdbc.sql("""
                insert into season (name, start_date, end_date) values
                ('Summer', '2026-04-01', '2026-10-31'), ('Winter', '2026-11-01', '2027-03-31'),
                ('Standard', '2026-01-01', '2026-12-31')""").update();
        jdbc.sql("insert into vehicle_type (name, min_capacity, max_capacity) values ('Car', 1, 2), ('Van', 3, 5)")
                .update();
        jdbc.sql("insert into destination (name, country) values ('Habarana', 'Sri Lanka')").update();
        jdbc.sql("insert into property (destination_id, name, code) values (1, 'Cinnamon Lodge', 'CIN')").update();
        jdbc.sql("insert into room_type (property_id, name) values (1, 'Deluxe')").update();
        jdbc.sql("insert into nationality (name, iso_code) values ('India', 'IN'), ('Japan', 'JP')").update();
        jdbc.sql("insert into tax_rate (name) values ('VAT')").update();
    }

    // ------------------------------------------------------------------ vehicle flat rate (fan-out)

    @Test
    void vehicleRateWithoutSeasonWritesEverySeason() {
        List<RateRowResponse> rows = service.setRate("vehicle", write(Map.of("vehicleTypeId", 1), "70", null), "prabash");

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.keys().get("seasonId")).containsExactly(1, 2, 3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.rate()).isEqualByComparingTo("70");
            assertThat(row.unit()).isEqualTo(RateUnit.LKR);
            assertThat(row.effectiveFrom()).isEqualTo(TODAY);
            assertThat(row.effectiveTo()).isNull();
            assertThat(row.isCurrent()).isTrue();
            assertThat(row.createdBy()).isEqualTo("prabash");
            assertThat(row.labels()).containsEntry("vehicleTypeId", "Car");
        });
        assertThat(rows.get(0).labels()).containsEntry("seasonId", "Summer");
    }

    @Test
    void vehicleRateWithASeasonOnlyTouchesThatSeason() {
        List<RateRowResponse> rows = service.setRate("vehicle",
                write(Map.of("vehicleTypeId", 1, "seasonId", 2), "70", null), "prabash");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).keys()).containsEntry("seasonId", 2);
    }

    @Test
    void fanOutConflictsWhenAnySeasonAlreadyStartedToday() {
        service.setRate("vehicle", write(Map.of("vehicleTypeId", 1, "seasonId", 2), "70", null), "a");

        // Season 2 already started today, so the all-season write cannot move it to the same day.
        assertConflict(() -> service.setRate("vehicle", write(Map.of("vehicleTypeId", 1), "80", null), "b"),
                "RATE_STARTS_BEFORE_CURRENT");
    }

    // ------------------------------------------------------------------ the close-and-open rule

    @Test
    void changingARateClosesTheOpenRowAndOpensANewOne() {
        service.setRate("vehicle", write(vehicle(1, 1), "70", null), "a");
        List<RateRowResponse> changed = service.setRate("vehicle", write(vehicle(1, 1), "80", "2026-11-01"), "b");

        assertThat(changed).hasSize(1);
        assertThat(changed.get(0).rate()).isEqualByComparingTo("80");
        assertThat(changed.get(0).isCurrent()).isFalse(); // starts in the future

        PageResponse<RateRowResponse> history = service.list("vehicle", Map.of("vehicleTypeId", "1", "seasonId", "1"),
                null, true, 0, 20);
        assertThat(history.content()).hasSize(2);
        assertThat(history.content().get(0).effectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(history.content().get(0).effectiveTo()).isNull();
        assertThat(history.content().get(1).effectiveTo()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void listShowsOnlyTheRateInForceOnTheRequestedDate() {
        service.setRate("vehicle", write(vehicle(1, 1), "70", null), "a");
        service.setRate("vehicle", write(vehicle(1, 1), "80", "2026-11-01"), "b");
        Map<String, String> filter = Map.of("vehicleTypeId", "1", "seasonId", "1");

        assertThat(service.list("vehicle", filter, null, false, 0, 20).content())
                .singleElement().satisfies(r -> assertThat(r.rate()).isEqualByComparingTo("70"));
        assertThat(service.list("vehicle", filter, LocalDate.of(2026, 11, 1), false, 0, 20).content())
                .singleElement().satisfies(r -> {
                    assertThat(r.rate()).isEqualByComparingTo("80");
                    assertThat(r.isCurrent()).isTrue();
                });
    }

    @Test
    void aRateMustStartAfterTheCurrentOne() {
        service.setRate("vehicle", write(vehicle(1, 1), "70", null), "a");

        assertConflict(() -> service.setRate("vehicle", write(vehicle(1, 1), "80", null), "b"),
                "RATE_STARTS_BEFORE_CURRENT");
        assertConflict(() -> service.setRate("vehicle", write(vehicle(1, 1), "80", "2026-10-01"), "b"),
                "RATE_STARTS_BEFORE_CURRENT");
    }

    @Test
    void aSecondPendingChangeIsRefused() {
        service.setRate("vehicle", write(vehicle(1, 1), "70", null), "a");
        service.setRate("vehicle", write(vehicle(1, 1), "80", "2026-11-01"), "b");

        assertConflict(() -> service.setRate("vehicle", write(vehicle(1, 1), "90", "2026-12-01"), "c"),
                "FUTURE_RATE_EXISTS");
    }

    @Test
    void deletingAPendingRateReopensThePreviousOne() {
        service.setRate("vehicle", write(vehicle(1, 1), "70", null), "a");
        int pendingId = service.setRate("vehicle", write(vehicle(1, 1), "80", "2026-11-01"), "b").get(0).id();

        service.deletePending("vehicle", pendingId);

        List<RateRowResponse> history = service.list("vehicle", Map.of("vehicleTypeId", "1", "seasonId", "1"), null,
                true, 0, 20).content();
        assertThat(history).singleElement().satisfies(r -> {
            assertThat(r.rate()).isEqualByComparingTo("70");
            assertThat(r.effectiveTo()).isNull();
        });
    }

    @Test
    void aRateThatAlreadyStartedCannotBeDeleted() {
        int id = service.setRate("vehicle", write(vehicle(1, 1), "70", null), "a").get(0).id();

        assertConflict(() -> service.deletePending("vehicle", id), "RATE_NOT_PENDING");
        assertThatThrownBy(() -> service.deletePending("vehicle", 9999)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void correctingFixesARowInPlace() {
        int id = service.setRate("vehicle", write(vehicle(1, 1), "70", null), "a").get(0).id();

        RateRowResponse fixed = service.correct("vehicle", id, new RateCorrectionRequest(new BigDecimal("72.50"), null, null));

        assertThat(fixed.id()).isEqualTo(id);
        assertThat(fixed.rate()).isEqualByComparingTo("72.50");
        assertThat(service.list("vehicle", Map.of(), null, true, 0, 50).totalElements()).isEqualTo(1);
    }

    @Test
    void correctingRejectsAnEmptyOrBackwardsChange() {
        int id = service.setRate("vehicle", write(vehicle(1, 1), "70", null), "a").get(0).id();

        assertThatThrownBy(() -> service.correct("vehicle", id, new RateCorrectionRequest(null, null, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.correct("vehicle", id, new RateCorrectionRequest(null, null, TODAY)))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getFieldErrors()).containsKey("effectiveTo"));
    }

    // ------------------------------------------------------------------ resolve

    @Test
    void resolveUsesTheExactRowThenFallsBackToAllNationalities() {
        service.setRate("property", write(propertyKeys(null), "100", null), "a");
        service.setRate("property", write(propertyKeys(1), "90", null), "a");

        ResolvedRateResponse india = service.resolve("property", resolveKeys("1"), null);
        assertThat(india.matched()).isEqualTo("EXACT");
        assertThat(india.rate()).isEqualByComparingTo("90");
        assertThat(india.unit()).isEqualTo(RateUnit.USD);

        ResolvedRateResponse japan = service.resolve("property", resolveKeys("2"), null);
        assertThat(japan.matched()).isEqualTo("FALLBACK_ALL_NATIONALITIES");
        assertThat(japan.rate()).isEqualByComparingTo("100");

        // Not naming a nationality selects the default row directly.
        assertThat(service.resolve("property", resolveKeys(null), null).matched()).isEqualTo("EXACT");
    }

    @Test
    void resolveReportsNoRateWhenNothingMatchesOrHasStartedYet() {
        service.setRate("property", write(propertyKeys(null), "100", "2026-11-01"), "a");

        assertThatThrownBy(() -> service.resolve("property", resolveKeys("1"), null))
                .isInstanceOfSatisfying(NotFoundException.class, e -> assertThat(e.getCode()).isEqualTo("NO_RATE"));
        assertThat(service.resolve("property", resolveKeys("1"), LocalDate.of(2026, 11, 1)).rate())
                .isEqualByComparingTo("100");

        Map<String, String> otherOccupancy = new java.util.HashMap<>(resolveKeys("1"));
        otherOccupancy.put("occupancy", "3");
        assertThatThrownBy(() -> service.resolve("property", otherOccupancy, LocalDate.of(2026, 11, 1)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void resolveRequiresEveryRequiredKey() {
        assertThatThrownBy(() -> service.resolve("property", Map.of("propertyId", "1"), null))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("keys.roomTypeId", "keys.seasonId", "keys.occupancy"));
    }

    // ------------------------------------------------------------------ validation

    @Test
    void invalidValuesAndKeysAreReportedPerField() {
        assertField(() -> service.setRate("vehicle", write(vehicle(1, 1), "-1", null), "a"), "rate");
        assertField(() -> service.setRate("vehicle", write(vehicle(1, 1), "70.005", null), "a"), "rate");
        assertField(() -> service.setRate("vehicle", write(vehicle(1, 1), "100000000", null), "a"), "rate");
        assertField(() -> service.setRate("tax", write(Map.of("taxRateId", 1), "100.01", null), "a"), "rate");
        assertField(() -> service.setRate("vehicle", write(Map.of("vehicleTypeId", 1, "colour", "red"), "70", null), "a"),
                "keys.colour");
        assertField(() -> service.setRate("vehicle", write(Map.of("seasonId", 1), "70", null), "a"),
                "keys.vehicleTypeId");
        assertField(() -> service.setRate("vehicle", write(Map.of("vehicleTypeId", "abc"), "70", null), "a"),
                "keys.vehicleTypeId");
    }

    @Test
    void unknownFamiliesAreNotFound() {
        assertThatThrownBy(() -> service.setRate("spaceship", write(Map.of(), "1", null), "a"))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ bulk adjust

    @Test
    void adjustDryRunPreviewsWithoutWriting() {
        service.setRate("vehicle", write(Map.of("vehicleTypeId", 1), "70", null), "a");

        List<RateAdjustItem> preview = service.adjust("vehicle", adjust(Map.of("vehicleTypeId", 1), 10, true, null), "b");

        assertThat(preview).hasSize(3).allSatisfy(item -> {
            assertThat(item.oldRate()).isEqualByComparingTo("70");
            assertThat(item.newRate()).isEqualByComparingTo("77");
            assertThat(item.unit()).isEqualTo(RateUnit.LKR);
        });
        assertThat(service.list("vehicle", Map.of(), null, true, 0, 50).totalElements()).isEqualTo(3);
    }

    @Test
    void adjustAppliesThroughTheSameRuleWithAFutureStart() {
        service.setRate("vehicle", write(Map.of("vehicleTypeId", 1), "70", null), "a");

        service.adjust("vehicle", adjust(Map.of("vehicleTypeId", 1), 10, false, LocalDate.of(2026, 11, 1)), "b");

        assertThat(service.list("vehicle", Map.of(), null, true, 0, 50).totalElements()).isEqualTo(6);
        assertThat(service.list("vehicle", Map.of(), null, false, 0, 50).content())
                .allSatisfy(r -> assertThat(r.rate()).isEqualByComparingTo("70"));
        assertThat(service.list("vehicle", Map.of(), LocalDate.of(2026, 11, 1), false, 0, 50).content())
                .hasSize(3).allSatisfy(r -> assertThat(r.rate()).isEqualByComparingTo("77"));
    }

    @Test
    void adjustFiltersByIdsAndIgnoresOtherRows() {
        service.setRate("vehicle", write(Map.of("vehicleTypeId", 1), "70", null), "a");
        service.setRate("vehicle", write(Map.of("vehicleTypeId", 2), "108", null), "a");

        List<RateAdjustItem> preview = service.adjust("vehicle",
                adjust(Map.of("vehicleTypeId", 2, "seasonId", List.of(1, 2)), 50, true, null), "b");

        assertThat(preview).hasSize(2).allSatisfy(item -> {
            assertThat(item.oldRate()).isEqualByComparingTo("108");
            assertThat(item.newRate()).isEqualByComparingTo("162");
        });
    }

    @Test
    void adjustRefusesAnOutOfRangeResultAndUnknownFilterKeys() {
        service.setRate("vehicle", write(Map.of("vehicleTypeId", 1), "70", null), "a");

        assertField(() -> service.adjust("vehicle", adjust(Map.of(), -150, true, null), "b"), "value");
        assertField(() -> service.adjust("vehicle", adjust(Map.of("colour", 1), 5, true, null), "b"), "filter.colour");
    }

    // ------------------------------------------------------------------ keyless family, grid, registry, paging

    @Test
    void transportHasNoKeysAndStillVersions() {
        service.setRate("transport", write(Map.of(), "71.50", null), "a");
        service.setRate("transport", write(Map.of(), "75", "2026-11-01"), "a");

        assertThat(service.resolve("transport", Map.of(), null).rate()).isEqualByComparingTo("71.50");
        assertThat(service.resolve("transport", Map.of(), LocalDate.of(2026, 11, 1)).rate()).isEqualByComparingTo("75");
    }

    @Test
    void gridSaveWritesEveryItemForThePropertyInTheUrl() {
        List<RateRowResponse> rows = service.saveGrid(1, List.of(
                write(propertyKeysWithoutProperty(null), "100", null),
                write(propertyKeysWithoutProperty(1), "90", null)), "prabash");

        assertThat(rows).hasSize(2).allSatisfy(r -> assertThat(r.keys()).containsEntry("propertyId", 1));
        assertThat(rows).extracting(r -> r.labels().get("propertyId")).containsOnly("Cinnamon Lodge");
        assertThat(rows).extracting(r -> r.labels().get("nationalityId")).containsExactly(null, "India");
    }

    @Test
    void gridSaveRejectsAMismatchedOrMissingProperty() {
        Map<String, Object> other = new java.util.HashMap<>(propertyKeysWithoutProperty(null));
        other.put("propertyId", 2);

        assertField(() -> service.saveGrid(1, List.of(write(other, "100", null)), "a"), "[0].keys.propertyId");
        assertThatThrownBy(() -> service.saveGrid(77, List.of(write(propertyKeysWithoutProperty(null), "100", null)), "a"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void familyRegistryDescribesKeysAndUnits() {
        List<RateFamilyResponse> families = service.families();

        assertThat(families).hasSize(10);
        RateFamilyResponse vehicle = families.stream().filter(f -> f.name().equals("vehicle")).findFirst().orElseThrow();
        assertThat(vehicle.unit()).isEqualTo(RateUnit.LKR);
        assertThat(vehicle.perKm()).isTrue();
        assertThat(vehicle.keys()).filteredOn(k -> k.name().equals("seasonId")).singleElement()
                .satisfies(k -> assertThat(k.required()).isFalse()); // omit = all seasons
        assertThat(families).filteredOn(f -> f.name().equals("tuk-tuk")).singleElement()
                .satisfies(f -> assertThat(f.unit()).isEqualTo(RateUnit.LKR));
        assertThat(families).filteredOn(f -> f.name().equals("tax")).singleElement()
                .satisfies(f -> assertThat(f.unit()).isEqualTo(RateUnit.PERCENT));
        assertThat(families).filteredOn(f -> f.name().equals("property")).singleElement()
                .satisfies(f -> assertThat(f.keys()).filteredOn(k -> k.name().equals("nationalityId")).singleElement()
                        .satisfies(k -> assertThat(k.nullMeansAll()).isTrue()));
    }

    @Test
    void listPaginates() {
        service.setRate("vehicle", write(Map.of("vehicleTypeId", 1), "70", null), "a");

        PageResponse<RateRowResponse> first = service.list("vehicle", Map.of(), null, false, 0, 2);
        PageResponse<RateRowResponse> second = service.list("vehicle", Map.of(), null, false, 1, 2);

        assertThat(first.content()).hasSize(2);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.first()).isTrue();
        assertThat(first.last()).isFalse();
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();

        assertThatThrownBy(() -> service.list("vehicle", Map.of(), null, false, 0, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list("vehicle", Map.of("colour", "red"), null, false, 0, 10))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void attractionRateTypesAreRestrictedToTheAllowedValues() {
        jdbc.sql("insert into attraction (destination_id, name, pricing_method) values (1, 'Sigiriya Rock', 'PER_PERSON')").update();

        assertThatThrownBy(() -> service.setRate("attraction",
                write(Map.of("attractionId", 1, "rateType", "Full Ticket"), "35", null), "a"))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors().get("keys.rateType"))
                        .contains("must be one of").contains("PER_PERSON").contains("CHILD_DISCOUNT"));
        assertThatThrownBy(() -> service.list("attraction", Map.of("rateType", "Half Ticket"), null, false, 0, 10))
                .isInstanceOf(InvalidRequestException.class);

        List<RateRowResponse> saved = service.setRate("attraction",
                write(Map.of("attractionId", 1, "rateType", "PER_PERSON"), "35", null), "a");
        assertThat(saved).singleElement().satisfies(r -> {
            assertThat(r.unit()).isEqualTo(RateUnit.USD);
            assertThat(r.labels()).containsEntry("attractionId", "Sigiriya Rock");
        });
    }

    @Test
    void theFamilyRegistryPublishesTheAllowedRateTypesAndTheGuideCurrency() {
        List<RateFamilyResponse> families = service.families();

        RateFamilyResponse attraction = families.stream().filter(f -> f.name().equals("attraction")).findFirst().orElseThrow();
        assertThat(attraction.keys()).filteredOn(k -> k.name().equals("rateType")).singleElement()
                .satisfies(k -> assertThat(k.allowedValues()).containsExactly("PER_PERSON", "FIXED_GROUP_BASE",
                        "EXTRA_PERSON", "GUEST_ENTRY_FEE", "GUIDE_ENTRY_FEE", "CHILD_DISCOUNT", "JEEP_RATE"));
        assertThat(attraction.keys()).filteredOn(k -> k.name().equals("attractionId")).singleElement()
                .satisfies(k -> assertThat(k.allowedValues()).isNull());

        // The guide accommodation figures supplied by the business are in LKR.
        assertThat(families).filteredOn(f -> f.name().equals("guide-accommodation")).singleElement()
                .satisfies(f -> assertThat(f.unit()).isEqualTo(RateUnit.LKR));
    }

    // ------------------------------------------------------------------ helpers

    private static RateWriteRequest write(Map<String, Object> keys, String rate, String from) {
        return new RateWriteRequest(keys, new BigDecimal(rate), from == null ? null : LocalDate.parse(from));
    }

    private static Map<String, Object> vehicle(int vehicleTypeId, int seasonId) {
        return Map.of("vehicleTypeId", vehicleTypeId, "seasonId", seasonId);
    }

    private static Map<String, Object> propertyKeysWithoutProperty(Integer nationalityId) {
        Map<String, Object> keys = new java.util.HashMap<>();
        keys.put("roomTypeId", 1);
        keys.put("seasonId", 1);
        keys.put("occupancy", 2);
        keys.put("nationalityId", nationalityId);
        return keys;
    }

    private static Map<String, Object> propertyKeys(Integer nationalityId) {
        Map<String, Object> keys = propertyKeysWithoutProperty(nationalityId);
        keys.put("propertyId", 1);
        return keys;
    }

    private static Map<String, String> resolveKeys(String nationalityId) {
        Map<String, String> keys = new java.util.HashMap<>(Map.of(
                "propertyId", "1", "roomTypeId", "1", "seasonId", "1", "occupancy", "2"));
        if (nationalityId != null) {
            keys.put("nationalityId", nationalityId);
        }
        return keys;
    }

    private static RateAdjustRequest adjust(Map<String, Object> filter, int percent, boolean dryRun, LocalDate from) {
        return new RateAdjustRequest(filter, RateAdjustRequest.Mode.PERCENT, new BigDecimal(percent), null, from, dryRun);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    private static void assertField(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getFieldErrors()).containsKey(field));
    }
}
