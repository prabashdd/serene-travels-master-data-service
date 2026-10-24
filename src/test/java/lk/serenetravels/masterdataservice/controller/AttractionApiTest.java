package lk.serenetravels.masterdataservice.controller;

import com.jayway.jsonpath.JsonPath;
import lk.serenetravels.masterdataservice.TestDatabase;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The attraction endpoints through the real security chain, JSON binding and error mapping. Business rules
// are covered in AttractionServiceTest; this checks roles, status codes, headers and the shapes the FE sees.
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
class AttractionApiTest {

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
    private int destinationId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (jdbc.sql("select count(*) from destination").query(Long.class).single() == 0) {
            jdbc.sql("insert into destination (name, country) values ('Sigiriya', 'Sri Lanka')").update();
        }
        destinationId = jdbc.sql("select min(id) from destination").query(Integer.class).single();
    }

    @Test
    void agentsCannotReadOrChangeAttractions() throws Exception {
        String agent = bearer("agent-user", "AGENT");

        mvc.perform(get(BASE + "/attractions").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/attractions").header(HttpHeaders.AUTHORIZATION, agent)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/attractions/1/tuk-tuk").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
    }

    @Test
    void aManagerCanCreateReadEditListAndDeleteAnAttraction() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String name = "Test Safari " + UUID.randomUUID().toString().substring(0, 6);

        String created = mvc.perform(post(BASE + "/attractions").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"destinationId\":" + destinationId
                                + ",\"pricingMethod\":\"PER_PERSON\",\"isSafari\":true,\"jeepCapacity\":6}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString("/attractions/")))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.isSafari").value(true))
                .andExpect(jsonPath("$.destination.name").value("Sigiriya"))
                .andExpect(jsonPath("$.currentRates.length()").value(0))
                .andExpect(jsonPath("$.tukTuk").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        mvc.perform(patch(BASE + "/attractions/" + id).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jeepCapacity\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jeepCapacity").doesNotExist())
                .andExpect(jsonPath("$.name").value(name));

        // sort=name,asc must reach the service whole, not split on the comma into two values
        mvc.perform(get(BASE + "/attractions").param("q", name).param("sort", "name,asc").param("safari", "true")
                        .header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].currentRates.length()").value(0));

        mvc.perform(delete(BASE + "/attractions/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/attractions/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void attractionErrorsComeBackAsProblemJson() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String name = "Dup " + UUID.randomUUID().toString().substring(0, 6);
        String body = "{\"name\":\"" + name + "\",\"destinationId\":" + destinationId + ",\"pricingMethod\":\"PER_PERSON\"}";

        mvc.perform(post(BASE + "/attractions").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pricingMethod\":\"Per Person\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.destinationId").exists());

        // With the required fields present, the values themselves are checked (the label is not the code).
        mvc.perform(post(BASE + "/attractions").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"destinationId\":" + destinationId + ",\"pricingMethod\":\"Per Person\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.pricingMethod").exists());

        mvc.perform(post(BASE + "/attractions").header(HttpHeaders.AUTHORIZATION, manager)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post(BASE + "/attractions").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));

        mvc.perform(get(BASE + "/attractions").param("sort", "secret,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sort").exists());
    }

    @Test
    void tukTukOptionAndFareWorkOverHttp() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String created = mvc.perform(post(BASE + "/attractions").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tuk " + UUID.randomUUID().toString().substring(0, 6) + "\",\"destinationId\":"
                                + destinationId + ",\"pricingMethod\":\"PER_PERSON\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        String option = mvc.perform(put(BASE + "/attractions/" + id + "/tuk-tuk").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentFare").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        int locationId = JsonPath.read(option, "$.locationId");

        // The fare is then set through the rate engine, keyed by the location the option returned.
        mvc.perform(put(BASE + "/rates/tuk-tuk").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":{\"tukTukLocationId\":" + locationId + "},\"rate\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unit").value("LKR"));
        mvc.perform(get(BASE + "/attractions/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.tukTuk.locationId").value(locationId))
                .andExpect(jsonPath("$.tukTuk.currentFare.rate").value(500.0))
                .andExpect(jsonPath("$.tukTuk.currentFare.unit").value("LKR"));

        mvc.perform(delete(BASE + "/attractions/" + id + "/tuk-tuk").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IN_USE"));
    }

    // ------------------------------------------------------------------ helpers

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
