package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.AttractionResponse;
import lk.serenetravels.masterdataservice.dto.AttractionSummaryResponse;
import lk.serenetravels.masterdataservice.dto.BabyCotRequest;
import lk.serenetravels.masterdataservice.dto.ChildRuleRequest;
import lk.serenetravels.masterdataservice.dto.DietarySupportRequest;
import lk.serenetravels.masterdataservice.dto.DriverResponse;
import lk.serenetravels.masterdataservice.dto.GuideResponse;
import lk.serenetravels.masterdataservice.dto.LocationDistanceResponse;
import lk.serenetravels.masterdataservice.dto.PackageResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.PromotionResponse;
import lk.serenetravels.masterdataservice.dto.PropertyInclusionRequest;
import lk.serenetravels.masterdataservice.dto.PropertyResponse;
import lk.serenetravels.masterdataservice.dto.PropertySummaryResponse;
import lk.serenetravels.masterdataservice.dto.RateRowResponse;
import lk.serenetravels.masterdataservice.dto.RateWriteRequest;
import lk.serenetravels.masterdataservice.dto.RestaurantResponse;
import lk.serenetravels.masterdataservice.dto.RoomTypeResponse;
import lk.serenetravels.masterdataservice.dto.VehicleResponse;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.service.impl.AttractionServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.DriverServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.GuideServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.LocationDistanceServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.LookupServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.PackageServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.PromotionServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.PropertyServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.RateServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.RestaurantServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.VehicleServiceImpl;
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

    @Test
    void hotelsReadRealDataAndWriteOnlyToSessionLocalCopies() throws Exception {
        try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, "postgres", "postgres", true)) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            Clock clock = Clock.system(ZoneId.of("Asia/Colombo"));
            RateServiceImpl rates = new RateServiceImpl(jdbc, clock);
            PropertyServiceImpl hotels = new PropertyServiceImpl(jdbc, clock, rates);

            List<String> realTables = List.of("property", "room_type", "property_child_rule", "property_inclusion",
                    "baby_cot_option", "property_dietary_capability");
            Map<String, Long> before = new java.util.LinkedHashMap<>();
            for (String table : realTables) {
                before.put(table, count(jdbc, "public." + table));
            }

            // ---- reads against the real hotels
            PageResponse<PropertySummaryResponse> page = hotels.list(null, null, null, null, null, 0, 5);
            assertThat(page.totalElements()).isEqualTo(before.get("property"));
            assertThat(page.content()).hasSize(5);
            assertThat(hotels.list("amaya", null, null, null, List.of("name,asc"), 0, 50).content())
                    .isNotEmpty().allSatisfy(h -> assertThat(h.name().toLowerCase()).contains("amaya"));
            assertThat(hotels.list(null, null, null, 4, List.of("starRating,desc", "name,asc"), 0, 5).content())
                    .allSatisfy(h -> assertThat(h.starRating()).isEqualTo(4));

            int richest = jdbc.sql("select property_id from room_type group by property_id order by count(*) desc, property_id limit 1")
                    .query(Integer.class).single();
            PropertyResponse detail = hotels.get(richest, false, null);
            assertThat(detail.roomTypes()).isNotEmpty();
            assertThat(detail.destination().name()).isNotBlank();

            // The imported rates are all historical (2018-2024), so look at one as of a date it was in force.
            Map<String, Object> legacy = jdbc.sql("select property_id, effective_from from property_rate order by id limit 1")
                    .query((rs, n) -> Map.<String, Object>of("property", rs.getInt(1),
                            "from", rs.getObject(2, LocalDate.class))).single();
            int priced = (int) legacy.get("property");
            assertThat(hotels.get(priced, true, (LocalDate) legacy.get("from")).rates()).isNotEmpty()
                    .allSatisfy(r -> assertThat(r.keys()).containsEntry("propertyId", priced));
            assertThat(hotels.get(priced, true, null).rates()).isEmpty();      // nothing is in force today
            assertThat(hotels.list(null, null, null, null, null, 0, 200).content()).hasSize(200)
                    .allSatisfy(h -> assertThat(h.fromRate()).isNull());       // so no hotel has a "from" rate yet

            // ---- writes against session-local shadows only
            for (String table : realTables) {
                jdbc.sql("create temp table " + table + " (like public." + table + " including all)").update();
            }
            int destinationId = jdbc.sql("select min(id) from public.destination").query(Integer.class).single();

            PropertyResponse created = hotels.create(new java.util.LinkedHashMap<>(Map.of(
                    "code", "SMOKE-1", "name", "Smoke Test Lodge", "destinationId", destinationId, "starRating", 4)));
            assertThat(created.id()).isNotNull();
            assertThatThrownBy(() -> hotels.create(new java.util.LinkedHashMap<>(Map.of(
                    "code", "SMOKE-1", "name", "Clash", "destinationId", destinationId))))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));

            RoomTypeResponse room = hotels.addRoomType(created.id(), new java.util.LinkedHashMap<>(Map.of(
                    "name", "Deluxe", "baseCapacity", 2)));
            assertThat(hotels.updateRoomType(room.id(), new java.util.LinkedHashMap<>(Map.of("extraBedAllowed", true)))
                    .extraBedAllowed()).isTrue();

            assertThat(hotels.replaceChildRules(created.id(), List.of(
                    new ChildRuleRequest(0, 5, false, BigDecimal.ZERO),
                    new ChildRuleRequest(6, 11, true, new BigDecimal("25"))))).hasSize(2);
            int inclusionId = jdbc.sql("select min(id) from public.hotel_inclusion").query(Integer.class).single();
            assertThat(hotels.replaceInclusions(created.id(), List.of(new PropertyInclusionRequest(inclusionId, null)))).hasSize(1);
            int categoryId = jdbc.sql("select min(id) from public.dietary_category").query(Integer.class).single();
            assertThat(hotels.replaceDietary(created.id(), List.of(new DietarySupportRequest(categoryId, true)))).hasSize(1);
            hotels.setBabyCot(created.id(), new BabyCotRequest(true, new BigDecimal("12")));
            hotels.setBabyCot(created.id(), new BabyCotRequest(false, null));   // second call takes the update path

            PropertyResponse full = hotels.get(created.id(), false, null);
            assertThat(full.roomTypes()).hasSize(1);
            assertThat(full.childRules()).hasSize(2);
            assertThat(full.inclusions()).hasSize(1);
            assertThat(full.dietary()).hasSize(1);
            assertThat(full.babyCot().available()).isFalse();

            hotels.deactivate(created.id());
            assertThat(hotels.get(created.id(), false, null).active()).isFalse();
            hotels.deleteRoomType(room.id());

            // ---- the real tables are exactly as they were
            for (String table : realTables) {
                assertThat(count(jdbc, "public." + table)).as(table).isEqualTo(before.get(table));
            }
        }
    }

    @Test
    void attractionsReadRealDataAndWriteOnlyToSessionLocalCopies() throws Exception {
        try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, "postgres", "postgres", true)) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            Clock clock = Clock.system(ZoneId.of("Asia/Colombo"));
            RateServiceImpl rates = new RateServiceImpl(jdbc, clock);
            AttractionServiceImpl attractions = new AttractionServiceImpl(jdbc, clock, rates);

            List<String> realTables = List.of("attraction", "attraction_rate", "tuk_tuk_location", "tuk_tuk_fare_rate");
            Map<String, Long> before = new java.util.LinkedHashMap<>();
            for (String table : realTables) {
                before.put(table, count(jdbc, "public." + table));
            }

            // ---- reads against the real attractions
            PageResponse<AttractionSummaryResponse> page = attractions.list(null, null, null, null, null, 0, 10);
            assertThat(page.totalElements()).isEqualTo(before.get("attraction"));
            assertThat(attractions.list("safari", null, true, null, List.of("name,asc"), 0, 50).content())
                    .isNotEmpty().allSatisfy(a -> {
                        assertThat(a.isSafari()).isTrue();
                        assertThat(a.name().toLowerCase()).contains("safari");
                    });
            assertThat(attractions.list(null, null, null, "PER_PERSON", List.of("destination,asc", "name,asc"), 0, 5).content())
                    .hasSize(5);
            int anyId = page.content().get(0).id();
            assertThat(attractions.get(anyId, null).destination().name()).isNotBlank();

            // ---- the real database refuses a pricing method outside its list (the label is not the code)
            assertThatThrownBy(() -> jdbc.sql("insert into public.attraction (destination_id, name, pricing_method) "
                    + "values (1, 'bad', 'Per Person')").update())
                    .isInstanceOfSatisfying(org.springframework.dao.DataIntegrityViolationException.class,
                            e -> assertThat(lk.serenetravels.masterdataservice.util.SqlErrors.sqlState(e)).isEqualTo("23514"));

            // ---- writes against session-local shadows only
            for (String table : realTables) {
                jdbc.sql("create temp table " + table + " (like public." + table + " including all)").update();
            }
            int destinationId = jdbc.sql("select min(id) from public.destination").query(Integer.class).single();

            AttractionResponse created = attractions.create(new java.util.LinkedHashMap<>(Map.of(
                    "name", "Smoke Safari", "destinationId", destinationId, "pricingMethod", "PER_PERSON",
                    "isSafari", true, "jeepCapacity", 6)));
            assertThat(created.id()).isNotNull();
            assertThatThrownBy(() -> attractions.create(new java.util.LinkedHashMap<>(Map.of(
                    "name", "smoke safari", "destinationId", destinationId, "pricingMethod", "PER_PERSON"))))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));

            AttractionResponse grouped = attractions.update(created.id(), new java.util.LinkedHashMap<>(Map.of(
                    "pricingMethod", "FIXED_GROUP_PLUS_EXTRA", "fixedGroupSize", 6)));
            assertThat(grouped.pricingMethod()).isEqualTo("FIXED_GROUP_PLUS_EXTRA");

            // Rates keyed by the real allowed rate types, on the shadowed attraction_rate.
            rates.setRate("attraction", new RateWriteRequest(
                    Map.of("attractionId", created.id(), "rateType", "FIXED_GROUP_BASE"), new BigDecimal("120"), null), "smoke-test");
            assertThatThrownBy(() -> rates.setRate("attraction", new RateWriteRequest(
                    Map.of("attractionId", created.id(), "rateType", "Full ticket"), new BigDecimal("35"), null), "smoke-test"))
                    .isInstanceOfSatisfying(lk.serenetravels.masterdataservice.exception.InvalidRequestException.class,
                            e -> assertThat(e.getFieldErrors()).containsKey("keys.rateType"));
            assertThat(attractions.get(created.id(), null).currentRates()).hasSize(1);
            assertThat(attractions.list("smoke", null, null, null, null, 0, 10).content().get(0).currentRates())
                    .singleElement().satisfies(r -> assertThat(r.rateType()).isEqualTo("FIXED_GROUP_BASE"));

            // Tuk-tuk option: created once (the real UNIQUE(attraction_id) is copied), then a fare in LKR.
            int locationId = attractions.enableTukTuk(created.id()).locationId();
            assertThat(attractions.enableTukTuk(created.id()).locationId()).isEqualTo(locationId);
            rates.setRate("tuk-tuk", new RateWriteRequest(Map.of("tukTukLocationId", locationId), new BigDecimal("500"), null), "smoke-test");
            assertThat(attractions.get(created.id(), null).tukTuk().currentFare().rate()).isEqualByComparingTo("500");

            // ---- the real tables are exactly as they were
            for (String table : realTables) {
                assertThat(count(jdbc, "public." + table)).as(table).isEqualTo(before.get(table));
            }
        }
    }

    @Test
    void fleetReadsRealDataAndWritesOnlyToSessionLocalCopies() throws Exception {
        try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, "postgres", "postgres", true)) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            Clock clock = Clock.system(ZoneId.of("Asia/Colombo"));
            VehicleServiceImpl vehicles = new VehicleServiceImpl(jdbc, clock);
            DriverServiceImpl drivers = new DriverServiceImpl(jdbc, clock);

            List<String> realTables = List.of("vehicle", "driver");
            Map<String, Long> before = new java.util.LinkedHashMap<>();
            for (String table : realTables) {
                before.put(table, count(jdbc, "public." + table));
            }

            // ---- reads against the real fleet
            var fleet = vehicles.list(null, null, null, null, null, List.of("registrationNo,asc"), 0, 50);
            assertThat(fleet.totalElements()).isEqualTo(before.get("vehicle"));
            assertThat(fleet.content()).allSatisfy(v -> {
                assertThat(v.vehicleType().name()).isNotBlank();
                assertThat(v.driver()).isNotNull();
            });
            int expiredOrSoon = vehicles.list(null, null, null, true, 30, null, 0, 50).content().size();
            assertThat(vehicles.list(null, null, null, null, 3650, null, 0, 50).content().size())
                    .isGreaterThanOrEqualTo(expiredOrSoon);
            assertThat(vehicles.list("lokuhetty", null, "CONTRACTED", true, null, List.of("type,asc", "insuranceExpiry,asc"), 0, 5)
                    .content()).isNotEmpty();

            var people = drivers.list(null, null, null, null, List.of("name,asc"), 0, 50);
            assertThat(people.totalElements()).isEqualTo(before.get("driver"));
            assertThat(people.content()).anySatisfy(p -> assertThat(p.vehicles()).isNotEmpty());
            int someDriver = fleet.content().get(0).driver().id();
            assertThat(drivers.get(someDriver).vehicles()).extracting(v -> v.name())
                    .contains(fleet.content().get(0).registrationNo());

            // ---- the real database refuses codes outside its lists
            assertThatThrownBy(() -> jdbc.sql("insert into public.driver (name, nic, role) values ('x', 'zz-1', 'BOSS')").update())
                    .isInstanceOfSatisfying(org.springframework.dao.DataIntegrityViolationException.class,
                            e -> assertThat(lk.serenetravels.masterdataservice.util.SqlErrors.sqlState(e)).isEqualTo("23514"));

            // ---- writes against session-local shadows only
            for (String table : realTables) {
                jdbc.sql("create temp table " + table + " (like public." + table + " including all)").update();
            }
            int typeId = jdbc.sql("select min(id) from public.vehicle_type").query(Integer.class).single();

            DriverResponse driver = drivers.create(new java.util.LinkedHashMap<>(Map.of(
                    "name", "Smoke Driver", "nic", "smoke-1", "gender", "male", "isChauffeur", true)));
            DriverResponse assistant = drivers.create(new java.util.LinkedHashMap<>(Map.of(
                    "name", "Smoke Assistant", "nic", "smoke-2", "role", "assistant")));
            assertThatThrownBy(() -> drivers.create(new java.util.LinkedHashMap<>(Map.of("name", "Clash", "nic", " SMOKE-1 "))))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));

            VehicleResponse created = vehicles.create(new java.util.LinkedHashMap<>(Map.of(
                    "registrationNo", "smoke-1", "vehicleTypeId", typeId, "seatingCapacity", 1, "method", "contracted",
                    "driverId", driver.id(), "assistantId", assistant.id(),
                    "insuranceExpiryDate", "2020-01-01")));
            assertThat(created.registrationNo()).isEqualTo("SMOKE-1");
            assertThat(created.warnings()).extracting(w -> w.code()).containsExactly("INSURANCE_EXPIRED");
            assertThatThrownBy(() -> vehicles.create(new java.util.LinkedHashMap<>(Map.of(
                    "registrationNo", "SMOKE-1", "vehicleTypeId", typeId, "seatingCapacity", 1, "method", "INTERNAL"))))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));

            assertThat(vehicles.update(created.id(), new java.util.LinkedHashMap<>(Map.of(
                    "insuranceExpiryDate", LocalDate.now(clock).plusYears(1).toString()))).warnings()).isEmpty();
            assertThatThrownBy(() -> drivers.update(driver.id(), new java.util.LinkedHashMap<>(Map.of("role", "ASSISTANT", "isChauffeur", false))))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("IN_USE"));
            drivers.deactivate(driver.id());
            assertThat(vehicles.get(created.id()).warnings()).extracting(w -> w.code()).containsExactly("DRIVER_INACTIVE");
            vehicles.deactivate(created.id());
            assertThat(vehicles.get(created.id()).warnings()).isEmpty();

            // ---- the real tables are exactly as they were
            for (String table : realTables) {
                assertThat(count(jdbc, "public." + table)).as(table).isEqualTo(before.get(table));
            }
        }
    }

    @Test
    void guidesReadRealDataAndWriteOnlyToSessionLocalCopies() throws Exception {
        try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, "postgres", "postgres", true)) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            GuideServiceImpl guides = new GuideServiceImpl(jdbc);

            List<String> realTables = List.of("guide", "guide_language");
            Map<String, Long> before = new java.util.LinkedHashMap<>();
            for (String table : realTables) {
                before.put(table, count(jdbc, "public." + table));
            }

            // ---- reads against the real roster
            var page = guides.list(null, null, null, null, List.of("guideNo,asc"), 0, 200);
            assertThat(page.totalElements()).isEqualTo(before.get("guide"));
            assertThat(page.content()).hasSize(200);
            assertThat(page.content()).anySatisfy(g -> assertThat(g.languages()).isNotEmpty());

            int english = jdbc.sql("select id from public.language where name = 'English'").query(Integer.class).single();
            var englishSpeakers = guides.list(null, null, english, true, List.of("name,asc"), 0, 50);
            assertThat(englishSpeakers.totalElements()).isGreaterThan(1000);
            assertThat(englishSpeakers.content()).allSatisfy(g ->
                    assertThat(g.languages()).extracting(l -> l.name()).contains("English"));
            assertThat(guides.list("athula", "NATIONAL_GUIDE", null, null, null, 0, 10).content()).isNotEmpty();
            int someId = page.content().get(0).id();
            assertThat(guides.get(someId).guideNo()).isEqualTo(page.content().get(0).guideNo());

            // ---- the real database refuses a guide type outside its list
            assertThatThrownBy(() -> jdbc.sql("insert into public.guide (name, guide_type, guide_no) values ('x', 'ASTRONAUT', 'zz-1')").update())
                    .isInstanceOfSatisfying(org.springframework.dao.DataIntegrityViolationException.class,
                            e -> assertThat(lk.serenetravels.masterdataservice.util.SqlErrors.sqlState(e)).isEqualTo("23514"));

            // ---- writes against session-local shadows only
            for (String table : realTables) {
                jdbc.sql("create temp table " + table + " (like public." + table + " including all)").update();
            }
            int german = jdbc.sql("select id from public.language where name = 'German'").query(Integer.class).single();

            GuideResponse created = guides.create(new java.util.LinkedHashMap<>(Map.of(
                    "guideNo", "smoke-1", "name", "Smoke Guide", "guideType", "chauffeur_guide", "nic", "smoke-nic",
                    "languageIds", List.of(english, german))));
            assertThat(created.guideNo()).isEqualTo("SMOKE-1");
            assertThat(created.languages()).extracting(l -> l.name()).containsExactly("English", "German");
            assertThatThrownBy(() -> guides.create(new java.util.LinkedHashMap<>(Map.of(
                    "guideNo", " Smoke-1 ", "name", "Clash", "guideType", "NATIONAL_GUIDE"))))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
            assertThatThrownBy(() -> guides.create(new java.util.LinkedHashMap<>(Map.of(
                    "guideNo", "smoke-2", "name", "Clash", "guideType", "NATIONAL_GUIDE", "nic", " SMOKE-NIC "))))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));

            assertThat(guides.replaceLanguages(created.id(), List.of(german))).extracting(l -> l.name()).containsExactly("German");
            assertThat(guides.update(created.id(), new java.util.LinkedHashMap<>(Map.of("town", "Kandy"))).town()).isEqualTo("Kandy");
            guides.deactivate(created.id());
            assertThat(guides.get(created.id()).active()).isFalse();

            // ---- the real tables are exactly as they were
            for (String table : realTables) {
                assertThat(count(jdbc, "public." + table)).as(table).isEqualTo(before.get(table));
            }
        }
    }

    @Test
    void restaurantsFollowTheRealDayConventionAndWriteOnlyToSessionLocalCopies() throws Exception {
        try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, "postgres", "postgres", true)) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            RestaurantServiceImpl restaurants = new RestaurantServiceImpl(jdbc);

            List<String> realTables = List.of("restaurant", "restaurant_closed_day", "restaurant_dietary_capability");
            Map<String, Long> before = new java.util.LinkedHashMap<>();
            for (String table : realTables) {
                before.put(table, count(jdbc, "public." + table));
            }

            // ---- the database documents the numbering we map to: 0 = Sunday .. 6 = Saturday
            String comment = jdbc.sql("""
                            select d.description from pg_description d join pg_class c on c.oid = d.objoid
                              join pg_attribute a on a.attrelid = c.oid and a.attnum = d.objsubid
                             where c.relname = 'restaurant_closed_day' and a.attname = 'day_of_week'""")
                    .query(String.class).single();
            assertThat(comment).contains("0 = Sunday").contains("6 = Saturday");
            for (int badDay : new int[]{-1, 7}) {
                assertThatThrownBy(() -> jdbc.sql("insert into public.restaurant_closed_day (restaurant_id, day_of_week) values (1, :d)")
                        .param("d", badDay).update())
                        .isInstanceOfSatisfying(org.springframework.dao.DataIntegrityViolationException.class,
                                e -> assertThat(lk.serenetravels.masterdataservice.util.SqlErrors.sqlState(e)).isEqualTo("23514"));
            }

            // ---- reads work against the real (currently empty) tables
            assertThat(restaurants.list(null, null, null, "monday", null, null, 0, 20).totalElements())
                    .isLessThanOrEqualTo(before.get("restaurant"));

            // ---- writes against session-local shadows only
            for (String table : realTables) {
                jdbc.sql("create temp table " + table + " (like public." + table + " including all)").update();
            }
            int destinationId = jdbc.sql("select min(id) from public.destination").query(Integer.class).single();
            int vegetarian = jdbc.sql("select id from public.dietary_category where name = 'Vegetarian'").query(Integer.class).single();
            int halal = jdbc.sql("select id from public.dietary_category where name = 'Halal'").query(Integer.class).single();

            RestaurantResponse created = restaurants.create(new java.util.LinkedHashMap<>(Map.of(
                    "name", "Smoke Kitchen", "destinationId", destinationId,
                    "closedDays", List.of("sunday", "Monday", "saturday"), "dietaryCategoryIds", List.of(vegetarian, halal))));
            assertThat(created.closedDays()).containsExactly("MONDAY", "SATURDAY", "SUNDAY");
            // stored using the database's own numbers: Sunday = 0, Monday = 1, Saturday = 6
            assertThat(jdbc.sql("select day_of_week from restaurant_closed_day where restaurant_id = :id order by day_of_week")
                    .param("id", created.id()).query(Integer.class).list()).containsExactly(0, 1, 6);

            assertThatThrownBy(() -> restaurants.create(new java.util.LinkedHashMap<>(Map.of(
                    "name", "SMOKE KITCHEN", "destinationId", destinationId))))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
            assertThat(restaurants.list(null, null, halal, "sunday", null, null, 0, 20).content()).hasSize(1);
            assertThat(restaurants.list(null, null, null, null, "tuesday", null, 0, 20).content()).hasSize(1);
            assertThat(restaurants.list(null, null, null, null, "monday", null, 0, 20).content()).isEmpty();

            assertThat(restaurants.replaceClosedDays(created.id(), List.of("Friday", "Thursday"))).containsExactly("THURSDAY", "FRIDAY");
            assertThat(restaurants.replaceDietary(created.id(), List.of(halal))).hasSize(1);
            restaurants.delete(created.id());
            assertThat(count(jdbc, "restaurant")).isZero();
            assertThat(count(jdbc, "restaurant_closed_day")).isZero();
            assertThat(count(jdbc, "restaurant_dietary_capability")).isZero();

            // ---- the real tables are exactly as they were
            for (String table : realTables) {
                assertThat(count(jdbc, "public." + table)).as(table).isEqualTo(before.get(table));
            }
        }
    }

    @Test
    void packagesPriceThroughTheRealPartialIndexAndWriteOnlyToSessionLocalCopies() throws Exception {
        try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, "postgres", "postgres", true)) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            Clock clock = Clock.system(ZoneId.of("Asia/Colombo"));
            RateServiceImpl rates = new RateServiceImpl(jdbc, clock);
            PackageServiceImpl packages = new PackageServiceImpl(jdbc, clock);

            List<String> realTables = List.of("site_tour_package", "site_tour_package_site", "site_tour_package_rate");
            Map<String, Long> before = new java.util.LinkedHashMap<>();
            for (String table : realTables) {
                before.put(table, count(jdbc, "public." + table));
            }

            // ---- reads work against the real (currently empty) tables
            assertThat(packages.list(null, null, null, List.of("name,asc"), 0, 20).totalElements()).isEqualTo(before.get("site_tour_package"));

            // ---- writes against session-local shadows only. The shadows keep the real UNIQUE(name) and the real
            // "one open price per package" partial index, but not the foreign keys, so the sites below are real attractions.
            for (String table : realTables) {
                jdbc.sql("create temp table " + table + " (like public." + table + " including all)").update();
            }
            List<Integer> attractionIds = jdbc.sql("select id from public.attraction order by id limit 3").query(Integer.class).list();
            assertThat(attractionIds).hasSize(3);

            PackageResponse created = packages.create(new java.util.LinkedHashMap<>(Map.of(
                    "name", "Smoke Triangle", "attractionIds", attractionIds)));
            assertThat(created.sites()).hasSize(3);
            assertThatThrownBy(() -> packages.create(new java.util.LinkedHashMap<>(Map.of("name", "smoke triangle"))))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));

            // The price is a USD rate on the real table's shape: set, revised (closing the first), read back.
            rates.setRate("package", new RateWriteRequest(Map.of("packageId", created.id()), new BigDecimal("150"), null), "smoke-test");
            assertThat(packages.get(created.id(), null).currentRate().rate()).isEqualByComparingTo("150");
            rates.setRate("package", new RateWriteRequest(Map.of("packageId", created.id()), new BigDecimal("165"),
                    LocalDate.now(clock).plusDays(30)), "smoke-test");
            assertThat(packages.get(created.id(), null).currentRate().rate()).isEqualByComparingTo("150");
            assertThat(packages.get(created.id(), LocalDate.now(clock).plusDays(30)).currentRate().rate()).isEqualByComparingTo("165");
            assertThat(packages.list("smoke", attractionIds.get(0), null, null, 0, 10).content()).singleElement()
                    .satisfies(p -> assertThat(p.currentRate().rate()).isEqualByComparingTo("150"));

            assertThat(packages.replaceSites(created.id(), List.of(attractionIds.get(1)))).hasSize(1);
            assertThatThrownBy(() -> packages.delete(created.id()))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("IN_USE"));
            PackageResponse spare = packages.create(new java.util.LinkedHashMap<>(Map.of("name", "Smoke Spare")));
            packages.delete(spare.id());
            assertThat(count(jdbc, "site_tour_package")).isEqualTo(1);

            // ---- the real tables are exactly as they were
            for (String table : realTables) {
                assertThat(count(jdbc, "public." + table)).as(table).isEqualTo(before.get(table));
            }
        }
    }

    @Test
    void promotionsFollowTheRealTypeListAndWriteOnlyToSessionLocalCopies() throws Exception {
        try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, "postgres", "postgres", true)) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            Clock clock = Clock.system(ZoneId.of("Asia/Colombo"));
            RateServiceImpl rates = new RateServiceImpl(jdbc, clock);
            PromotionServiceImpl promotions = new PromotionServiceImpl(jdbc, clock);

            List<String> realTables = List.of("promotion", "promotion_value");
            Map<String, Long> before = new java.util.LinkedHashMap<>();
            for (String table : realTables) {
                before.put(table, count(jdbc, "public." + table));
            }

            // ---- the real database only accepts the three types the API publishes
            assertThatThrownBy(() -> jdbc.sql("insert into public.promotion (name, type, valid_from, valid_to) "
                    + "values ('bad', 'BOGO', '2026-01-01', '2026-02-01')").update())
                    .isInstanceOfSatisfying(org.springframework.dao.DataIntegrityViolationException.class,
                            e -> assertThat(lk.serenetravels.masterdataservice.util.SqlErrors.sqlState(e)).isEqualTo("23514"));

            // ---- reads work against the real (currently empty) tables
            assertThat(promotions.list(null, "PERCENTAGE", true, LocalDate.now(clock), null, null, null, List.of("validFrom,desc"), 0, 20)
                    .totalElements()).isLessThanOrEqualTo(before.get("promotion"));

            // ---- writes against session-local shadows only (real constraints and the open-value partial index kept)
            for (String table : realTables) {
                jdbc.sql("create temp table " + table + " (like public." + table + " including all)").update();
            }
            int destinationId = jdbc.sql("select min(id) from public.destination").query(Integer.class).single();
            int seasonId = jdbc.sql("select min(id) from public.season").query(Integer.class).single();
            LocalDate today = LocalDate.now(clock);

            PromotionResponse created = promotions.create(new java.util.LinkedHashMap<>(Map.of(
                    "name", "Smoke Early Bird", "type", "percentage", "validFrom", today.minusDays(1).toString(),
                    "validTo", today.plusDays(30).toString(), "minGroupSize", 4, "destinationId", destinationId,
                    "seasonId", seasonId)));
            assertThat(created.status()).isEqualTo("RUNNING");

            // The value goes through the real "value" column; as a percentage it is reported in PERCENT and capped at 100.
            RateRowResponse row = rates.setRate("promotion", new RateWriteRequest(
                    Map.of("promotionId", created.id()), new BigDecimal("12.5"), null), "smoke-test").get(0);
            assertThat(row.unit()).isEqualTo(lk.serenetravels.masterdataservice.dto.RateUnit.PERCENT);
            assertThatThrownBy(() -> rates.setRate("promotion", new RateWriteRequest(
                    Map.of("promotionId", created.id()), new BigDecimal("101"), today.plusDays(5)), "smoke-test"))
                    .isInstanceOf(lk.serenetravels.masterdataservice.exception.InvalidRequestException.class);
            assertThat(promotions.get(created.id(), null).currentValue().rate()).isEqualByComparingTo("12.5");

            // Once priced, the type is locked; the real window check (valid_to > valid_from) is mirrored by the API.
            assertThatThrownBy(() -> promotions.update(created.id(), new java.util.LinkedHashMap<>(Map.of("type", "FIXED_AMOUNT"))))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("TYPE_LOCKED"));
            assertThatThrownBy(() -> promotions.update(created.id(), new java.util.LinkedHashMap<>(Map.of("validTo", today.minusDays(5).toString()))))
                    .isInstanceOf(lk.serenetravels.masterdataservice.exception.InvalidRequestException.class);

            promotions.deactivate(created.id());
            assertThat(promotions.list("smoke", null, false, null, null, null, null, null, 0, 10).content()).hasSize(1);
            assertThat(count(jdbc, "promotion")).isEqualTo(1);

            // ---- the real tables are exactly as they were
            for (String table : realTables) {
                assertThat(count(jdbc, "public." + table)).as(table).isEqualTo(before.get(table));
            }
        }
    }

    @Test
    void distancesReadTheRealRowsBothWaysAndWriteOnlyToSessionLocalCopies() throws Exception {
        try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, "postgres", "postgres", true)) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            LocationDistanceServiceImpl distances = new LocationDistanceServiceImpl(jdbc);

            long before = count(jdbc, "public.location_distance");

            // ---- reads against the real rows
            PageResponse<LocationDistanceResponse> all = distances.list(null, null, null, null, null, null, null, 0, 5);
            assertThat(all.totalElements()).isEqualTo(before);
            assertThat(all.content()).isNotEmpty().allSatisfy(d -> assertThat(d.from().name()).isNotBlank());

            // the suspect-row filter agrees with a direct count of the same rule
            long suspect = jdbc.sql("select count(*) from public.location_distance where from_destination_id = to_destination_id "
                    + "or distance_km <= 0").query(Long.class).single();
            assertThat(distances.list(null, null, null, null, null, true, null, 0, 5).totalElements()).isEqualTo(suspect);
            assertThat(distances.list(null, null, null, null, null, false, null, 0, 5).totalElements()).isEqualTo(before - suspect);

            // a pair stored in one direction is found from both ends
            LocationDistanceResponse stored = distances.list(null, null, null, null, null, false, List.of("id,asc"), 0, 1).content().get(0);
            List<LocationDistanceResponse> forward = distances.between(stored.from().id(), stored.to().id());
            List<LocationDistanceResponse> backward = distances.between(stored.to().id(), stored.from().id());
            assertThat(forward).extracting(LocationDistanceResponse::id).contains(stored.id())
                    .containsExactlyElementsOf(backward.stream().map(LocationDistanceResponse::id).toList());

            // the real UNIQUE (from, to, route_type) is what backs the duplicate rule
            assertThatThrownBy(() -> jdbc.sql("insert into public.location_distance (from_destination_id, to_destination_id, "
                    + "route_type, distance_km) values (:f, :t, :r, 1)").param("f", stored.from().id())
                    .param("t", stored.to().id()).param("r", stored.routeType()).update())
                    .isInstanceOfSatisfying(org.springframework.dao.DataIntegrityViolationException.class,
                            e -> assertThat(lk.serenetravels.masterdataservice.util.SqlErrors.sqlState(e)).isEqualTo("23505"));

            // ---- writes against a session-local shadow only (the real unique constraint is kept)
            jdbc.sql("create temp table location_distance (like public.location_distance including all)").update();
            int a = stored.from().id();
            int b = stored.to().id();

            LocationDistanceResponse created = distances.create(new java.util.LinkedHashMap<>(Map.of(
                    "fromDestinationId", a, "toDestinationId", b, "routeType", "road", "distanceKm", 123.4, "durationMinutes", 200)));
            assertThat(created.distanceKm()).isEqualByComparingTo("123.4");     // numeric(6,1) keeps one decimal
            assertThatThrownBy(() -> distances.create(new java.util.LinkedHashMap<>(Map.of(
                    "fromDestinationId", b, "toDestinationId", a, "routeType", "ROAD", "distanceKm", 5))))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
            assertThat(distances.between(b, a)).hasSize(1);        // the shadow hides the real rows for this session
            assertThat(distances.update(created.id(), new java.util.LinkedHashMap<>(Map.of("distanceKm", 130))).distanceKm())
                    .isEqualByComparingTo("130");
            distances.delete(created.id());
            assertThat(count(jdbc, "location_distance")).isZero();

            // ---- the real table is exactly as it was
            assertThat(count(jdbc, "public.location_distance")).isEqualTo(before);
        }
    }

    private static long count(JdbcClient jdbc, String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }
}
