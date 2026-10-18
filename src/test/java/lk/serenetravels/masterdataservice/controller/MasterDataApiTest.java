package lk.serenetravels.masterdataservice.controller;

import lk.serenetravels.masterdataservice.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Full Spring context (security chain, method security, controllers, services, SQL) over an in-memory
// database, driven through the real bearer-token path: a mocked JwtDecoder hands back realm_access
// roles exactly where Keycloak puts them, so the production role converter is what grants access.
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
class MasterDataApiTest {

    private static final String BASE = "/api/v1/master-data";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::newUrl);
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbc.sql("delete from vehicle_rate").update();
        if (jdbc.sql("select count(*) from season").query(Long.class).single() == 0) {
            jdbc.sql("""
                    insert into season (name, start_date, end_date) values
                    ('Summer', '2026-04-01', '2026-10-31'), ('Winter', '2026-11-01', '2027-03-31'),
                    ('Standard', '2026-01-01', '2026-12-31')""").update();
            jdbc.sql("insert into vehicle_type (name, min_capacity, max_capacity) values ('Car', 1, 2), ('Van', 3, 5)")
                    .update();
        }
    }

    // ------------------------------------------------------------------ authentication & roles

    @Test
    void requestsWithoutATokenAreRejectedAsProblemJson() throws Exception {
        mvc.perform(get(BASE + "/lookups"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void anInvalidTokenIsRejectedAsProblemJson() throws Exception {
        when(jwtDecoder.decode("bad")).thenThrow(new BadJwtException("expired"));

        mvc.perform(get(BASE + "/lookups").header(HttpHeaders.AUTHORIZATION, "Bearer bad"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void agentsAreForbiddenEverywhere() throws Exception {
        String agent = bearer("agent-user", "AGENT");

        mvc.perform(get(BASE + "/lookups").header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(BASE + "/rates/vehicle").header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/rates/resolve").param("family", "vehicle").header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminsAndManagersMayRead() throws Exception {
        for (String role : List.of("ADMIN", "MANAGER")) {
            mvc.perform(get(BASE + "/lookups").header(HttpHeaders.AUTHORIZATION, bearer(role + "-user", role)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void theQuotationServiceMayOnlyResolveRates() throws Exception {
        String quotation = bearer("quotation-svc", "QUOTATION_SERVICE");
        String manager = bearer("boss", "MANAGER");

        mvc.perform(get(BASE + "/lookups").header(HttpHeaders.AUTHORIZATION, quotation))
                .andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/rates/vehicle").header(HttpHeaders.AUTHORIZATION, quotation)
                        .contentType(MediaType.APPLICATION_JSON).content(vehicleRate("70")))
                .andExpect(status().isForbidden());

        // Authorised but nothing is priced yet: a business 404, not a 403.
        mvc.perform(resolveVehicle(quotation))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_RATE"));

        mvc.perform(put(BASE + "/rates/vehicle").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content(vehicleRate("70")))
                .andExpect(status().isOk());

        mvc.perform(resolveVehicle(quotation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(70.0))
                .andExpect(jsonPath("$.unit").value("LKR"))
                .andExpect(jsonPath("$.matched").value("EXACT"));
    }

    @Test
    void swaggerSpecIsPublicAndDocumentsTheEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/master-data/rates/{family}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/master-data/lookups']").exists());
    }

    // ------------------------------------------------------------------ lookups bundle

    @Test
    void lookupBundleCarriesAnETagAndAnswers304OnRevalidation() throws Exception {
        String manager = bearer("boss", "MANAGER");

        String etag = mvc.perform(get(BASE + "/lookups").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(jsonPath("$.seasons.length()").value(3))
                .andExpect(jsonPath("$.vehicleTypes[0].name").value("Car"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mvc.perform(get(BASE + "/lookups").header(HttpHeaders.AUTHORIZATION, manager)
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
    }

    // ------------------------------------------------------------------ rates over HTTP

    @Test
    void settingAVehicleRateRecordsTheKeycloakUsernameAndFansOutAcrossSeasons() throws Exception {
        mvc.perform(put(BASE + "/rates/vehicle").header(HttpHeaders.AUTHORIZATION, bearer("kc-prabash", "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(vehicleRate("70")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].createdBy").value("kc-prabash"))
                .andExpect(jsonPath("$[0].isCurrent").value(true))
                .andExpect(jsonPath("$[0].unit").value("LKR"))
                .andExpect(jsonPath("$[0].labels.vehicleTypeId").value("Car"));
    }

    @Test
    void aFailedFanOutRollsBackEverySeasonItAlreadyWrote() throws Exception {
        String manager = bearer("boss", "MANAGER");
        // Season 2 already holds a rate that started today, so a later all-season write must fail on it...
        mvc.perform(put(BASE + "/rates/vehicle").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":{\"vehicleTypeId\":2,\"seasonId\":2},\"rate\":108}"))
                .andExpect(status().isOk());

        mvc.perform(put(BASE + "/rates/vehicle").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":{\"vehicleTypeId\":2},\"rate\":120}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATE_STARTS_BEFORE_CURRENT"));

        // ...and seasons 1 and 3, which the loop wrote before reaching season 2, must not be left behind.
        assertThat(jdbc.sql("select count(*) from vehicle_rate where vehicle_type_id = 2").query(Long.class).single())
                .isEqualTo(1L);
    }

    @Test
    void validationErrorsComeBackAsProblemJsonWithFieldErrors() throws Exception {
        mvc.perform(put(BASE + "/rates/vehicle").header(HttpHeaders.AUTHORIZATION, bearer("boss", "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":{\"vehicleTypeId\":1,\"colour\":\"red\"},\"rate\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(put(BASE + "/rates/vehicle").header(HttpHeaders.AUTHORIZATION, bearer("boss", "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":{\"vehicleTypeId\":1},\"rate\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.rate").exists());
    }

    @Test
    void beanValidationAndMalformedJsonAreBadRequests() throws Exception {
        String manager = bearer("boss", "MANAGER");

        mvc.perform(put(BASE + "/rates/vehicle").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rate\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.keys").exists());
        mvc.perform(put(BASE + "/rates/vehicle").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unknownRateFamilyIs404() throws Exception {
        mvc.perform(get(BASE + "/rates/spaceship").header(HttpHeaders.AUTHORIZATION, bearer("boss", "MANAGER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void listAndFamiliesEndpointsUseTheDocumentedShapes() throws Exception {
        String manager = bearer("boss", "MANAGER");
        mvc.perform(put(BASE + "/rates/vehicle").header(HttpHeaders.AUTHORIZATION, manager)
                .contentType(MediaType.APPLICATION_JSON).content(vehicleRate("70"))).andExpect(status().isOk());

        mvc.perform(get(BASE + "/rates/vehicle").param("filter[vehicleTypeId]", "1")
                        .header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].keys.vehicleTypeId").value(1))
                .andExpect(jsonPath("$.first").value(true));

        mvc.perform(get(BASE + "/rates/families").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[?(@.name=='tuk-tuk')].unit").value("LKR"));
    }

    // ------------------------------------------------------------------ helpers

    private static String vehicleRate(String rate) {
        return "{\"keys\":{\"vehicleTypeId\":1},\"rate\":" + rate + "}";
    }

    private org.springframework.test.web.servlet.RequestBuilder resolveVehicle(String authorization) {
        return get(BASE + "/rates/resolve").param("family", "vehicle")
                .param("keys[vehicleTypeId]", "1").param("keys[seasonId]", "1")
                .header(HttpHeaders.AUTHORIZATION, authorization);
    }

    // Registers a decodable token for `username` carrying `roles` in realm_access.roles, the claim
    // Keycloak uses, and returns the Authorization header value.
    private String bearer(String username, String... roles) {
        String token = "token-" + username + "-" + String.join("-", roles);
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(username + "-sub")
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode(token)).thenReturn(jwt);
        return "Bearer " + token;
    }
}
