package lk.serenetravels.masterdataservice.docs;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.docs.ApiReferenceRenderer.Sample;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static lk.serenetravels.masterdataservice.docs.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Builds docs/API-REFERENCE.md and keeps it honest.
 *
 * <p>The test plays a realistic story against the real application (security, JSON, error handling, an in-memory
 * database) - set up lookups, a hotel, attractions, a fleet, rates, alerts... - and records every call marked
 * {@code .doc()} as a sample. The renderer combines those samples with what the controllers declare. A normal run
 * regenerates the document in memory and FAILS if it differs from the committed file, so the reference can never
 * quietly go stale. To refresh it after a deliberate API change:
 * <pre>mvnw test -Dtest=ApiReferenceTest -Dapi.docs.write=true</pre>
 * The clock is fixed (18 Oct 2026, Colombo) and all database ids are fresh, so the output is the same on every run.
 */
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
@Import(ApiReferenceTest.FixedClock.class)
class ApiReferenceTest {

    private static final String BASE = "/api/v1/master-data";
    private static final Path OUTPUT = Path.of("docs", "API-REFERENCE.md");
    private static final Path INTRO = Path.of("docs", "api-reference", "intro.md");
    private static final String FIXED_CREATED_AT = "2026-10-18T03:00:00Z";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Colombo"));
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::newUrl);
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private MockMvc mvc;
    private final List<Sample> samples = new ArrayList<>();
    private final List<Sample> commonSamples = new ArrayList<>();
    private final Map<String, String> tokens = new LinkedHashMap<>();

    // ids of the records the story creates, reused by later steps
    private int destSigiriya, destKandy, destGalle, destColombo, destYala;
    private int natIndia, natUk;
    private int seasonSummer, seasonWinter;
    private int typeCar, typeVan;
    private int langEnglish, langGerman;
    private int dietVeg, dietHalal;
    private int incTransfer, incDrink;
    private int hotelLotus, hotelKandy, roomDeluxe, roomFamily;
    private int rock, pidurangala, safari;
    private int driverNimal, assistantSunil;
    private int vanOne, vanTwo;
    private int guideAthula, restaurantKamatha, packageCultural, promoEarlyBird, promoFixed;
    private int distanceColomboSigiriya, articleSigiriya;

    // ------------------------------------------------------------------ the test

    @Test
    void referenceMatchesTheCode() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tokens.put("MANAGER", bearer("nimali.manager", "MANAGER"));
        tokens.put("AGENT", bearer("agent.silva", "AGENT"));
        tokens.put("QUOTATION_SERVICE", bearer("quotation-service", "QUOTATION_SERVICE"));

        lookups();
        hotels();
        attractions();
        fleet();
        guides();
        restaurants();
        packages();
        promotions();
        distances();
        articles();
        rates();
        readsThatShowRates();
        alerts();
        commonErrors();

        Map<String, Object> apiDocs = apiDocs();
        ApiReferenceRenderer renderer = new ApiReferenceRenderer(Files.readString(INTRO, StandardCharsets.UTF_8)
                .replace("\r\n", "\n"), apiDocs, samples, commonSamples, roles());

        Set<String> operations = new TreeSet<>(renderer.operationKeys());
        Set<String> sampled = renderer.sampledKeys();
        Set<String> unsampled = new TreeSet<>(operations);
        unsampled.removeAll(sampled);
        Set<String> unknown = new TreeSet<>(sampled);
        unknown.removeAll(operations);
        assertThat(unknown).as("samples for operations that no longer exist").isEmpty();
        assertThat(unsampled).as("operations with no sample - add a .doc() call for each in ApiReferenceTest").isEmpty();

        String generated = renderer.render();
        if (Boolean.getBoolean("api.docs.write")) {
            Files.writeString(OUTPUT, generated, StandardCharsets.UTF_8);
            return;
        }
        if (!Files.exists(OUTPUT)) {
            fail("docs/API-REFERENCE.md does not exist. Generate it: mvnw test -Dtest=ApiReferenceTest -Dapi.docs.write=true");
        }
        String committed = Files.readString(OUTPUT, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!generated.equals(committed)) {
            fail("docs/API-REFERENCE.md is out of date (" + firstDifference(committed, generated) + "). Regenerate it with: "
                    + "mvnw test -Dtest=ApiReferenceTest -Dapi.docs.write=true  - then review and commit the change.");
        }
    }

    // ------------------------------------------------------------------ the story

    private void lookups() {
        destSigiriya = call("POST", "/lookups/{name}", "destinations").title("Add a destination")
                .body(obj("name", "Sigiriya", "region", "Cultural Triangle", "country", "Sri Lanka")).expect(201).doc().id();
        destKandy = seedLookup("destinations", obj("name", "Kandy", "region", "Central Province", "country", "Sri Lanka"));
        destGalle = seedLookup("destinations", obj("name", "Galle", "region", "Southern Province", "country", "Sri Lanka"));
        destColombo = seedLookup("destinations", obj("name", "Colombo", "region", "Western Province", "country", "Sri Lanka"));
        destYala = seedLookup("destinations", obj("name", "Yala", "region", "Southern Province", "country", "Sri Lanka"));
        natIndia = seedLookup("nationalities", obj("name", "India", "isoCode", "IN"));
        natUk = seedLookup("nationalities", obj("name", "United Kingdom", "isoCode", "GB"));
        seasonSummer = call("POST", "/lookups/{name}", "seasons").title("Add a season")
                .body(obj("name", "Summer", "startDate", "2026-04-01", "endDate", "2026-10-31")).expect(201).doc().id();
        seasonWinter = seedLookup("seasons", obj("name", "Winter", "startDate", "2026-11-01", "endDate", "2027-03-31"));
        typeCar = seedLookup("vehicle-types", obj("name", "Car", "minCapacity", 1, "maxCapacity", 2));
        typeVan = seedLookup("vehicle-types", obj("name", "Van", "minCapacity", 3, "maxCapacity", 9));
        langEnglish = seedLookup("languages", obj("name", "English"));
        langGerman = seedLookup("languages", obj("name", "German"));
        dietVeg = seedLookup("dietary-categories", obj("name", "Vegetarian"));
        dietHalal = seedLookup("dietary-categories", obj("name", "Halal"));
        seedLookup("meal-plans", obj("code", "BB", "name", "Bed and breakfast"));
        incTransfer = seedLookup("hotel-inclusions", obj("name", "Airport transfer", "description", "One-way, up to 3 guests"));
        incDrink = seedLookup("hotel-inclusions", obj("name", "Welcome drink", "description", null));
        seedLookup("misc-cost-types", obj("name", "Camera fee"));
        seedLookup("tax-rates", obj("name", "VAT"));
        seedLookup("technical-visit-categories", obj("name", "Tea factory"));

        Resp bundle = call("GET", "/lookups").title("Fetch every lookup in one call").expect(200).doc();
        call("GET", "/lookups").title("Revalidate the cached bundle (nothing changed)")
                .header(HttpHeaders.IF_NONE_MATCH, bundle.etag()).expect(304).doc();
        call("GET", "/lookups/{name}", "seasons").title("Fetch one lookup list").expect(200).doc();
        call("PATCH", "/lookups/{name}/{id}", "destinations", destSigiriya).title("Edit a lookup item (only the changed fields)")
                .body(obj("region", "North Central Province")).expect(200).doc();
        int temporary = seedLookup("destinations", obj("name", "Temporary Place", "country", "Sri Lanka"));
        call("DELETE", "/lookups/{name}/{id}", "destinations", temporary).title("Delete an unused lookup item")
                .expect(204).doc();
    }

    private void hotels() {
        hotelLotus = call("POST", "/properties").title("Create a hotel")
                .body(obj("code", "LGH-SGR", "name", "Lotus Garden Hotel", "destinationId", destSigiriya,
                        "address", "Inamaluwa Road, Sigiriya", "starRating", 4, "website", "https://lotusgarden.example",
                        "email", "reservations@lotusgarden.example", "phone", "+94 66 228 0000")).expect(201).doc().id();
        hotelKandy = call("POST", "/properties").body(obj("code", "KHR-KDY", "name", "Kandy Hills Resort",
                "destinationId", destKandy, "starRating", 5)).expect(201).seed().id();

        roomDeluxe = call("POST", "/properties/{id}/room-types", hotelLotus).title("Add a room type")
                .body(obj("name", "Deluxe Double", "baseCapacity", 2, "extraBedAllowed", true)).expect(201).doc().id();
        roomFamily = call("POST", "/properties/{id}/room-types", hotelLotus)
                .body(obj("name", "Family Suite", "baseCapacity", 4)).expect(201).seed().id();
        call("PATCH", "/room-types/{id}", roomFamily).title("Edit a room type (only the changed fields)")
                .body(obj("extraBedAllowed", true)).expect(200).doc();
        int temporaryRoom = call("POST", "/properties/{id}/room-types", hotelLotus)
                .body(obj("name", "Temporary Room")).expect(201).seed().id();
        call("DELETE", "/room-types/{id}", temporaryRoom).title("Delete a room type").expect(204).doc();

        call("PUT", "/properties/{id}/child-rules", hotelLotus).title("Rejected: overlapping age bands are reported per item")
                .body(List.of(obj("minAge", 0, "maxAge", 6, "requiresBed", false, "cost", 0),
                        obj("minAge", 5, "maxAge", 11, "requiresBed", true, "cost", 20))).expect(400).doc();
        call("PUT", "/properties/{id}/child-rules", hotelLotus).title("Replace the child pricing rules")
                .body(List.of(obj("minAge", 0, "maxAge", 5, "requiresBed", false, "cost", 0),
                        obj("minAge", 6, "maxAge", 11, "requiresBed", true, "cost", 20))).expect(200).doc();
        call("PUT", "/properties/{id}/inclusions", hotelLotus).title("Replace the inclusions (cost null = included free)")
                .body(List.of(obj("inclusionId", incTransfer, "cost", 15), obj("inclusionId", incDrink, "cost", null)))
                .expect(200).doc();
        call("PUT", "/properties/{id}/dietary", hotelLotus).title("Replace the dietary support")
                .body(List.of(obj("dietaryCategoryId", dietVeg, "supported", true),
                        obj("dietaryCategoryId", dietHalal, "supported", false))).expect(200).doc();
        call("PUT", "/properties/{id}/baby-cot", hotelLotus).title("Set the baby cot option")
                .body(obj("available", true, "cost", 10)).expect(200).doc();

        call("PATCH", "/properties/{id}", hotelLotus).title("Quick edit (only the changed fields; null clears)")
                .body(obj("phone", "+94 66 228 1111", "website", null)).expect(200).doc();
        int old = call("POST", "/properties").body(obj("code", "OGH-GLL", "name", "Old Guest House",
                "destinationId", destGalle)).expect(201).seed().id();
        call("DELETE", "/properties/{id}", old).title("Deactivate a hotel (soft delete)").expect(204).doc();

        call("GET", "/properties").title("Search hotels").q("q", "lotus").q("sort", "name,asc").q("size", "5").expect(200).doc();
        call("GET", "/properties/{id}", hotelLotus).title("One hotel with everything attached").expect(200).doc();
    }

    private void attractions() {
        rock = call("POST", "/attractions").title("Create an attraction")
                .body(obj("name", "Sigiriya Rock Fortress", "destinationId", destSigiriya, "pricingMethod", "PER_PERSON",
                        "isSafari", false)).expect(201).doc().id();
        safari = call("POST", "/attractions").title("Create a safari priced as a fixed group plus extras")
                .body(obj("name", "Yala Jeep Safari", "destinationId", destYala, "pricingMethod", "FIXED_GROUP_PLUS_EXTRA",
                        "isSafari", true, "jeepCapacity", 6, "fixedGroupSize", 6)).expect(201).doc().id();
        pidurangala = call("POST", "/attractions").body(obj("name", "Pidurangala Rock", "destinationId", destSigiriya,
                "pricingMethod", "PER_PERSON")).expect(201).seed().id();
        call("PATCH", "/attractions/{id}", rock).title("Quick edit")
                .body(obj("name", "Sigiriya Rock Fortress (Lion Rock)")).expect(200).doc();
        int spare = call("POST", "/attractions").body(obj("name", "Spare Attraction", "destinationId", destGalle,
                "pricingMethod", "PER_PERSON")).expect(201).seed().id();
        call("DELETE", "/attractions/{id}", spare).title("Delete an attraction nothing refers to").expect(204).doc();

        call("PUT", "/attractions/{id}/tuk-tuk", rock).title("Enable the tuk-tuk option").expect(200).doc();
        call("PUT", "/attractions/{id}/tuk-tuk", pidurangala).expect(200).seed();
        call("DELETE", "/attractions/{id}/tuk-tuk", pidurangala).title("Remove the tuk-tuk option").expect(204).doc();

        call("GET", "/attractions").title("Search attractions").q("q", "sigiriya").q("sort", "name,asc").expect(200).doc();
    }

    private void fleet() {
        driverNimal = call("POST", "/drivers").title("Create a driver")
                .body(obj("name", "Nimal Perera", "nic", "851234567V", "role", "DRIVER", "contact", "0771234567",
                        "licenseNo", "B1234567", "isChauffeur", false)).expect(201).doc().id();
        assistantSunil = call("POST", "/drivers").title("Create an assistant")
                .body(obj("name", "Sunil Fernando", "nic", "902345678V", "role", "ASSISTANT")).expect(201).doc().id();
        call("PATCH", "/drivers/{id}", driverNimal).title("Quick edit").body(obj("contact", "0779876543", "mobile2", "0112223344"))
                .expect(200).doc();
        int leaver = call("POST", "/drivers").body(obj("name", "Retired Driver", "nic", "700000000V")).expect(201).seed().id();
        call("DELETE", "/drivers/{id}", leaver).title("Deactivate a driver (soft delete)").expect(204).doc();

        vanOne = call("POST", "/vehicles").title("Create a vehicle with its driver and assistant")
                .body(obj("registrationNo", "CAB-1234", "vehicleTypeId", typeVan, "seatingCapacity", 7, "method", "INTERNAL",
                        "ownerName", "Serene Travels", "insuranceCompany", "Ceylinco", "policyNumber", "P-77881",
                        "insuranceExpiryDate", "2026-11-02", "revenueLicenseExpiryDate", "2027-03-31",
                        "driverId", driverNimal, "assistantId", assistantSunil)).expect(201).doc().id();
        vanTwo = call("POST", "/vehicles").title("Create a vehicle with no driver and an expired insurance")
                .body(obj("registrationNo", "WP-9999", "vehicleTypeId", typeVan, "seatingCapacity", 8, "method", "CONTRACTED",
                        "ownerName", "K. Silva", "insuranceExpiryDate", "2026-10-01", "revenueLicenseExpiryDate", "2027-06-30"))
                .expect(201).doc().id();
        call("PATCH", "/vehicles/{id}", vanOne).title("Quick edit").body(obj("ownerName", "Serene Travels (Pvt) Ltd"))
                .expect(200).doc();
        int scrapped = call("POST", "/vehicles").body(obj("registrationNo", "OLD-0001", "vehicleTypeId", typeCar,
                "seatingCapacity", 2, "method", "INTERNAL")).expect(201).seed().id();
        call("DELETE", "/vehicles/{id}", scrapped).title("Take a vehicle out of service (soft delete)").expect(204).doc();

        call("GET", "/drivers").title("Search drivers").q("role", "DRIVER").q("sort", "name,asc").expect(200).doc();
        call("GET", "/drivers/{id}", driverNimal).title("One driver").expect(200).doc();
        call("GET", "/vehicles").title("Vehicles whose documents expire within 30 days").q("expiringWithinDays", "30")
                .q("sort", "insuranceExpiry,asc").expect(200).doc();
        call("GET", "/vehicles/{id}", vanOne).title("One vehicle, with its warnings").expect(200).doc();
    }

    private void guides() {
        guideAthula = call("POST", "/guides").title("Create a guide with the languages spoken")
                .body(obj("guideNo", "N-0101", "name", "Athula Bandara", "guideType", "NATIONAL_GUIDE", "nickname", "Athula",
                        "nic", "781234567V", "contact", "0777490642", "email", "athula@example.com", "town", "Kandy",
                        "languageIds", List.of(langEnglish, langGerman))).expect(201).doc().id();
        call("PATCH", "/guides/{id}", guideAthula).title("Quick edit").body(obj("town", "Matale", "nickname", null))
                .expect(200).doc();
        call("PUT", "/guides/{id}/languages", guideAthula).title("Replace the languages spoken")
                .body(List.of(langEnglish)).expect(200).doc();
        int retired = call("POST", "/guides").body(obj("guideNo", "C-0900", "name", "Retired Guide",
                "guideType", "CHAUFFEUR_GUIDE")).expect(201).seed().id();
        call("DELETE", "/guides/{id}", retired).title("Deactivate a guide (soft delete)").expect(204).doc();
        call("GET", "/guides").title("Search guides by language").q("languageId", String.valueOf(langEnglish))
                .q("sort", "name,asc").expect(200).doc();
        call("GET", "/guides/{id}", guideAthula).title("One guide").expect(200).doc();
    }

    private void restaurants() {
        restaurantKamatha = call("POST", "/restaurants").title("Create a restaurant with closed days and dietary support")
                .body(obj("name", "Kamatha Restaurant", "destinationId", destSigiriya, "closedDays", List.of("MONDAY"),
                        "dietaryCategoryIds", List.of(dietVeg))).expect(201).doc().id();
        call("PATCH", "/restaurants/{id}", restaurantKamatha).title("Rename or move a restaurant")
                .body(obj("name", "Kamatha Garden Restaurant")).expect(200).doc();
        call("PUT", "/restaurants/{id}/closed-days", restaurantKamatha).title("Replace the closed days (day names)")
                .body(List.of("MONDAY", "TUESDAY")).expect(200).doc();
        call("PUT", "/restaurants/{id}/dietary", restaurantKamatha).title("Replace the dietary support")
                .body(List.of(dietVeg, dietHalal)).expect(200).doc();
        int closed = call("POST", "/restaurants").body(obj("name", "Closed Down Cafe", "destinationId", destKandy))
                .expect(201).seed().id();
        call("DELETE", "/restaurants/{id}", closed).title("Delete a restaurant").expect(204).doc();
        call("GET", "/restaurants").title("Restaurants open on a Monday that cater for halal")
                .q("openOn", "monday").q("dietaryCategoryId", String.valueOf(dietHalal)).expect(200).doc();
        call("GET", "/restaurants/{id}", restaurantKamatha).title("One restaurant").expect(200).doc();
    }

    private void packages() {
        packageCultural = call("POST", "/packages").title("Create a package with its sites")
                .body(obj("name", "Cultural Triangle Day Tour", "attractionIds", List.of(rock))).expect(201).doc().id();
        call("PUT", "/packages/{id}/sites", packageCultural).title("Replace the included attractions")
                .body(List.of(rock, pidurangala)).expect(200).doc();
        call("PATCH", "/packages/{id}", packageCultural).title("Rename a package")
                .body(obj("name", "Cultural Triangle Two-Rock Day Tour")).expect(200).doc();
        int spare = call("POST", "/packages").body(obj("name", "Spare Package")).expect(201).seed().id();
        call("DELETE", "/packages/{id}", spare).title("Delete a package that was never priced").expect(204).doc();
        call("GET", "/packages").title("Packages that include an attraction").q("attractionId", String.valueOf(rock))
                .expect(200).doc();
    }

    private void promotions() {
        promoEarlyBird = call("POST", "/promotions").title("Create a percentage promotion")
                .body(obj("name", "Early Bird 2027", "type", "PERCENTAGE", "validFrom", "2026-10-01", "validTo", "2027-01-01",
                        "minGroupSize", 4)).expect(201).doc().id();
        promoFixed = call("POST", "/promotions").title("Create a fixed-amount promotion for one destination")
                .body(obj("name", "Sigiriya Weekend", "type", "FIXED_AMOUNT", "validFrom", "2026-12-01",
                        "validTo", "2027-03-01", "destinationId", destSigiriya, "seasonId", seasonWinter)).expect(201).doc().id();
        call("PATCH", "/promotions/{id}", promoEarlyBird).title("Quick edit (null clears a limit)")
                .body(obj("minGroupSize", 6)).expect(200).doc();
        int old = call("POST", "/promotions").body(obj("name", "Old Offer", "type", "COMPLIMENTARY",
                "validFrom", "2025-01-01", "validTo", "2025-06-01")).expect(201).seed().id();
        call("DELETE", "/promotions/{id}", old).title("Deactivate a promotion (soft delete)").expect(204).doc();
        call("GET", "/promotions").title("Promotions running today").q("validOn", "2026-10-18").q("active", "true")
                .expect(200).doc();
    }

    private void distances() {
        distanceColomboSigiriya = call("POST", "/location-distances").title("Add a distance")
                .body(obj("fromDestinationId", destColombo, "toDestinationId", destSigiriya, "routeType", "ROAD",
                        "distanceKm", "175.5", "durationMinutes", 270)).expect(201).doc().id();
        call("POST", "/location-distances").body(obj("fromDestinationId", destSigiriya, "toDestinationId", destKandy,
                "routeType", "ROAD", "distanceKm", 90)).expect(201).seed();
        call("POST", "/location-distances").title("Rejected: the same route is already recorded (either direction)")
                .body(obj("fromDestinationId", destSigiriya, "toDestinationId", destColombo, "routeType", "ROAD",
                        "distanceKm", 176)).expect(409).doc();
        call("PATCH", "/location-distances/{id}", distanceColomboSigiriya).title("Quick edit")
                .body(obj("distanceKm", 176.2, "durationMinutes", null)).expect(200).doc();
        int spare = call("POST", "/location-distances").body(obj("fromDestinationId", destGalle, "toDestinationId", destKandy,
                "routeType", "ROADA", "distanceKm", 210)).expect(201).seed().id();
        call("DELETE", "/location-distances/{id}", spare).title("Delete a distance").expect(204).doc();
        call("GET", "/location-distances/between").title("Every route between two destinations, whichever way it is stored")
                .q("fromDestinationId", String.valueOf(destSigiriya)).q("toDestinationId", String.valueOf(destColombo))
                .expect(200).doc();
        call("GET", "/location-distances").title("Distances from or to a destination")
                .q("destinationId", String.valueOf(destSigiriya)).q("sort", "distanceKm,asc").expect(200).doc();
        call("GET", "/location-distances/{id}", distanceColomboSigiriya).title("One distance").expect(200).doc();
    }

    private void articles() {
        articleSigiriya = call("POST", "/info-articles").title("Create a destination article")
                .body(obj("category", "GENERAL", "destinationId", destSigiriya, "title", "Sigiriya",
                        "body", "SIGIRIYA\nThe 5th-century rock fortress rises 200 m above the jungle plain.\n"
                                + "Climb early to beat the heat and the crowds.")).expect(201).doc().id();
        call("POST", "/info-articles").title("Create a country-wide article (no destination)")
                .body(obj("category", "VISA", "title", "Entry requirements",
                        "body", "Most visitors need an Electronic Travel Authorisation (ETA) before arrival."))
                .expect(201).doc();
        call("PATCH", "/info-articles/{id}", articleSigiriya).title("Quick edit")
                .body(obj("title", "Sigiriya Rock Fortress")).expect(200).doc();
        int spare = call("POST", "/info-articles").body(obj("category", "WEATHER", "title", "Spare note", "body", "Temporary."))
                .expect(201).seed().id();
        call("DELETE", "/info-articles/{id}", spare).title("Delete an article").expect(204).doc();
        call("GET", "/info-articles").title("Articles for a destination plus the country-wide ones")
                .q("destinationId", String.valueOf(destSigiriya)).q("includeCountryWide", "true").expect(200).doc();
        call("GET", "/info-articles/{id}", articleSigiriya).title("One article with its full body").expect(200).doc();
    }

    private void rates() {
        // vehicle: priced per vehicle TYPE; leaving seasonId out writes every season at once
        call("PUT", "/rates/{family}", "vehicle").title("Set a rate for every season at once (the season key is left out)")
                .body(obj("keys", obj("vehicleTypeId", typeVan), "rate", 95)).expect(200).doc();

        // attraction: a price, then a future price that closes the first when it starts
        Resp first = call("PUT", "/rates/{family}", "attraction").title("Set a rate (starts today unless effectiveFrom is given)")
                .body(obj("keys", obj("attractionId", rock, "rateType", "PER_PERSON"), "rate", 35)).expect(200).doc();
        call("PUT", "/rates/{family}", "attraction").title("Rejected: a new rate must start after the current one started")
                .body(obj("keys", obj("attractionId", rock, "rateType", "PER_PERSON"), "rate", 36)).expect(409).doc();
        call("PUT", "/rates/{family}", "attraction").title("Rejected: a rate cannot be negative")
                .body(obj("keys", obj("attractionId", rock, "rateType", "PER_PERSON"), "rate", -5)).expect(400).doc();
        Resp future = call("PUT", "/rates/{family}", "attraction").title("Schedule a future price change")
                .body(obj("keys", obj("attractionId", rock, "rateType", "PER_PERSON"), "rate", 40,
                        "effectiveFrom", "2026-12-01")).expect(200).doc();
        call("PUT", "/rates/{family}", "attraction").body(obj("keys", obj("attractionId", rock, "rateType", "CHILD_DISCOUNT"), "rate", 17.5))
                .expect(200).seed();
        call("GET", "/rates/{family}", "attraction").title("Rate history for one attraction (history=true includes closed rows)")
                .q("filter[attractionId]", String.valueOf(rock)).q("history", "true").expect(200).doc();
        call("PATCH", "/rates/{family}/{id}", "attraction", first.firstRowId()).title("Correct a typo in place")
                .body(obj("rate", 35.5)).expect(200).doc();
        call("DELETE", "/rates/{family}/{id}", "attraction", future.firstRowId())
                .title("Delete a rate that has not started yet (history is never deleted)").expect(204).doc();

        // the property grid
        call("PUT", "/properties/{id}/rates", hotelLotus).title("Save a hotel's whole rate grid atomically")
                .body(List.of(
                        obj("keys", obj("roomTypeId", roomDeluxe, "seasonId", seasonSummer, "occupancy", 2), "rate", 85),
                        obj("keys", obj("roomTypeId", roomDeluxe, "seasonId", seasonSummer, "occupancy", 1), "rate", 70),
                        obj("keys", obj("roomTypeId", roomDeluxe, "seasonId", seasonWinter, "occupancy", 2,
                                "nationalityId", natIndia), "rate", 110)))
                .expect(200).doc();
        call("GET", "/rates/{family}", "property").title("Current rates of one hotel in one season")
                .q("filter[propertyId]", String.valueOf(hotelLotus)).q("filter[seasonId]", String.valueOf(seasonSummer))
                .expect(200).doc();

        // other families
        Resp tukLocation = call("GET", "/attractions/{id}", rock).expect(200).seed();
        int locationId = ((Number) ((Map<?, ?>) tukLocation.map().get("tukTuk")).get("locationId")).intValue();
        call("PUT", "/rates/{family}", "tuk-tuk").title("Set a tuk-tuk fare (LKR)")
                .body(obj("keys", obj("tukTukLocationId", locationId), "rate", 1500)).expect(200).doc();
        Resp packageRate = call("PUT", "/rates/{family}", "package").title("Price a package")
                .body(obj("keys", obj("packageId", packageCultural), "rate", 120)).expect(200).doc();
        call("PUT", "/rates/{family}", "promotion").title("Set a promotion's value (a percentage here, because of its type)")
                .body(obj("keys", obj("promotionId", promoEarlyBird), "rate", 10)).expect(200).doc();
        call("PUT", "/rates/{family}", "promotion").body(obj("keys", obj("promotionId", promoFixed), "rate", 50)).expect(200).seed();
        call("PATCH", "/rates/{family}/{id}", "package", packageRate.firstRowId())
                .title("Set an end date on a rate (it will then show up in the alerts)")
                .body(obj("effectiveTo", "2026-10-30")).expect(200).doc();

        // adjust
        call("POST", "/rates/{family}/adjust", "vehicle").title("Preview a bulk +8% change (dry run writes nothing)")
                .body(obj("filter", obj("vehicleTypeId", typeVan), "mode", "PERCENT", "value", 8, "rounding", "NEAREST_1",
                        "effectiveFrom", "2026-11-01", "dryRun", true)).expect(200).doc();
        call("POST", "/rates/{family}/adjust", "vehicle").title("Apply it")
                .body(obj("filter", obj("vehicleTypeId", typeVan), "mode", "PERCENT", "value", 8, "rounding", "NEAREST_1",
                        "effectiveFrom", "2026-11-01", "dryRun", false)).expect(200).doc();

        // metadata and resolve
        call("GET", "/rates/families").title("Which rate families exist and what keys they take").expect(200).doc();
        call("GET", "/rates/resolve").title("Resolve one price (falls back to the 'all nationalities' row)")
                .as("QUOTATION_SERVICE").q("family", "property")
                .q("keys[propertyId]", String.valueOf(hotelLotus)).q("keys[roomTypeId]", String.valueOf(roomDeluxe))
                .q("keys[seasonId]", String.valueOf(seasonSummer)).q("keys[occupancy]", "2")
                .q("keys[nationalityId]", String.valueOf(natUk)).expect(200).doc();
        call("GET", "/rates/resolve").title("Nothing priced for that combination")
                .q("family", "vehicle").q("keys[vehicleTypeId]", String.valueOf(typeCar)).q("keys[seasonId]", String.valueOf(seasonSummer))
                .expect(404).doc();
    }

    private void readsThatShowRates() {
        call("GET", "/properties/{id}", hotelLotus).title("The same hotel with its current rates (include=rates)")
                .q("include", "rates").expect(200).doc();
        call("GET", "/attractions/{id}", rock).title("One attraction with its rates and tuk-tuk fare").expect(200).doc();
        call("GET", "/packages/{id}", packageCultural).title("One package with its sites and current price").expect(200).doc();
        call("GET", "/promotions/{id}", promoEarlyBird).title("One promotion with its status and current value").expect(200).doc();
        call("DELETE", "/lookups/{name}/{id}", "destinations", destSigiriya)
                .title("Rejected: the item is still used by other master data").expect(409).doc();
    }

    private void alerts() {
        call("GET", "/alerts").title("Things needing attention (next 30 days)").q("size", "10").expect(200).doc();
        call("GET", "/alerts").title("Only one kind of alert").q("code", "RATE_ENDING_SOON").expect(200).doc();
        call("GET", "/alerts/summary").title("Counts for a dashboard badge").expect(200).doc();
    }

    private void commonErrors() {
        call("GET", "/lookups").title("No token").as(null).expect(401).common();
        call("GET", "/lookups").title("A token without the ADMIN or MANAGER role (agents are never allowed)").as("AGENT")
                .expect(403).common();
        call("POST", "/properties").title("Validation failed: every problem is listed against its field").body(obj())
                .expect(400).common();
        call("POST", "/properties").title("Malformed JSON").rawBody("{not json").expect(400).common();
        call("GET", "/properties/{id}", 999999).title("Not found").expect(404).common();
        call("POST", "/properties").title("Duplicate")
                .body(obj("code", "LGH-SGR", "name", "Another Lotus", "destinationId", destSigiriya)).expect(409).common();
    }

    // ------------------------------------------------------------------ calling the API

    private int seedLookup(String lookup, Object body) {
        return call("POST", "/lookups/{name}", lookup).body(body).expect(201).seed().id();
    }

    private Call call(String method, String template, Object... pathVariables) {
        return new Call(method, template, pathVariables);
    }

    private final class Call {
        private final String method;
        private final String template;
        private final Object[] pathVariables;
        private String title;
        private Object body;
        private String rawBody;
        private final List<String[]> query = new ArrayList<>();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private int expected = -1;
        private String role = "MANAGER";

        Call(String method, String template, Object[] pathVariables) {
            this.method = method;
            this.template = template;
            this.pathVariables = pathVariables;
        }

        Call title(String value) {
            this.title = value;
            return this;
        }

        Call body(Object value) {
            this.body = value;
            return this;
        }

        Call rawBody(String value) {
            this.rawBody = value;
            return this;
        }

        Call q(String name, String value) {
            query.add(new String[]{name, value});
            return this;
        }

        Call header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        Call expect(int status) {
            this.expected = status;
            return this;
        }

        Call as(String roleName) {
            this.role = roleName;
            return this;
        }

        Resp doc() {
            return run(1);
        }

        Resp seed() {
            return run(0);
        }

        Resp common() {
            return run(2);
        }

        // mode: 0 = just do it, 1 = record as a sample of this operation, 2 = record as a common error example
        private Resp run(int mode) {
            try {
                String path = BASE + fill(template, pathVariables);
                MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
                for (String[] pair : query) {
                    request.param(pair[0], pair[1]);
                }
                String requestJson = rawBody != null ? rawBody : body == null ? null : Json.compact(body);
                if (requestJson != null) {
                    request.contentType(MediaType.APPLICATION_JSON).content(requestJson);
                }
                if (role != null) {
                    request.header(HttpHeaders.AUTHORIZATION, tokens.get(role));
                }
                headers.forEach(request::header);

                MvcResult result = mvc.perform(request).andReturn();
                int status = result.getResponse().getStatus();
                if (expected >= 0 && status != expected) {
                    throw new AssertionError(method + " " + path + " expected " + expected + " but was " + status + ": "
                            + result.getResponse().getContentAsString());
                }
                String responseText = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                Object parsed = responseText.isBlank() ? null : Json.parse(responseText);
                parsed = normalise(parsed);
                Resp resp = new Resp(status, parsed, result.getResponse().getHeader(HttpHeaders.LOCATION),
                        result.getResponse().getHeader(HttpHeaders.ETAG));
                if (mode != 0) {
                    Sample sample = new Sample(title, method, BASE + template, path, query, headers,
                            requestJson == null ? null : rawBody != null ? rawBody : Json.pretty(Json.parse(requestJson)), status, resp.location(),
                            resp.etag(), parsed == null ? null : Json.pretty(parsed));
                    (mode == 1 ? samples : commonSamples).add(sample);
                }
                return resp;
            } catch (Exception ex) {
                throw new IllegalStateException(method + " " + template + ": " + ex.getMessage(), ex);
            }
        }
    }

    private record Resp(int status, Object body, String location, String etag) {
        int id() {
            return ((Number) map().get("id")).intValue();
        }

        // The id of the first row of a rate write (a list of rate rows).
        int firstRowId() {
            if (body instanceof List<?> rows) {
                return ((Number) ((Map<?, ?>) rows.get(0)).get("id")).intValue();
            }
            return id();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map() {
            return (Map<String, Object>) body;
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]+}");

    private static String fill(String template, Object[] variables) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(variables[index++])));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // Things that differ from run to run without meaning anything are pinned, so the document is byte-identical each time:
    // timestamps the database fills in with the real time, and the order of fieldErrors (the service builds it with an
    // immutable Map, whose iteration order is randomised per JVM start - a client must not rely on it).
    @SuppressWarnings("unchecked")
    private static Object normalise(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> {
                if ("createdAt".equals(k) && v instanceof String) {
                    copy.put(k, FIXED_CREATED_AT);
                } else if ("fieldErrors".equals(k) && v instanceof Map<?, ?> errors) {
                    Map<String, Object> sorted = new java.util.TreeMap<>((Map<String, Object>) errors);
                    copy.put(k, new LinkedHashMap<>(sorted));
                } else {
                    copy.put(k, normalise(v));
                }
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(normalise(item)));
            return copy;
        }
        return value;
    }

    // ------------------------------------------------------------------ document inputs

    @SuppressWarnings("unchecked")
    private Map<String, Object> apiDocs() throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return (Map<String, Object>) Json.parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // "GET /api/v1/master-data/properties" -> "ADMIN, MANAGER", read from the @PreAuthorize on the method or its class.
    private Map<String, String> roles() {
        Pattern role = Pattern.compile("'([A-Z_]+)'");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getName().startsWith("lk.serenetravels")) {
                continue;
            }
            PreAuthorize rule = handler.getMethodAnnotation(PreAuthorize.class);
            if (rule == null) {
                rule = handler.getBeanType().getAnnotation(PreAuthorize.class);
            }
            if (rule == null || entry.getKey().getPathPatternsCondition() == null) {
                continue;
            }
            List<String> names = new ArrayList<>();
            Matcher matcher = role.matcher(rule.value());
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            String text = String.join(", ", names);
            for (String pattern : entry.getKey().getPathPatternsCondition().getPatternValues()) {
                entry.getKey().getMethodsCondition().getMethods()
                        .forEach(m -> result.put(m.name() + " " + pattern, text));
            }
        }
        return result;
    }

    private String bearer(String username, String... roleNames) {
        String token = "token-" + username;
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(username + "-sub")
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(roleNames)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(jwtDecoder.decode(token)).thenReturn(jwt);
        return "Bearer " + token;
    }

    private static String firstDifference(String committed, String generated) {
        String[] a = committed.split("\n", -1);
        String[] b = generated.split("\n", -1);
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (!a[i].equals(b[i])) {
                return "first difference at line " + (i + 1) + ": committed \"" + abbreviate(a[i]) + "\" vs generated \""
                        + abbreviate(b[i]) + "\"";
            }
        }
        return "committed has " + a.length + " lines, generated has " + b.length;
    }

    private static String abbreviate(String line) {
        return line.length() <= 80 ? line : line.substring(0, 77) + "...";
    }
}
