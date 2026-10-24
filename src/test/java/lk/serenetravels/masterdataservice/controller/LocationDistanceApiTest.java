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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The distance endpoints through the real security chain, JSON binding and error mapping. Business rules are
// covered in LocationDistanceServiceTest; this checks roles, status codes and the shapes the FE sees.
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
class LocationDistanceApiTest {

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
    private int fromId;
    private int toId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        for (String name : List.of("Dist Alpha", "Dist Beta")) {
            if (jdbc.sql("select count(*) from destination where name = :n").param("n", name).query(Long.class).single() == 0) {
                jdbc.sql("insert into destination (name, country) values (:n, 'Sri Lanka')").param("n", name).update();
            }
        }
        fromId = jdbc.sql("select id from destination where name = 'Dist Alpha'").query(Integer.class).single();
        toId = jdbc.sql("select id from destination where name = 'Dist Beta'").query(Integer.class).single();
    }

    @Test
    void agentsCannotReadOrChangeDistances() throws Exception {
        String agent = bearer("agent-user", "AGENT");

        mvc.perform(get(BASE + "/location-distances").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/location-distances/between").param("fromDestinationId", "1").param("toDestinationId", "2")
                .header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/location-distances").header(HttpHeaders.AUTHORIZATION, agent)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void aManagerCanRunDistancesEndToEnd() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String route = "RT" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();

        String created = mvc.perform(post(BASE + "/location-distances").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDestinationId\":" + fromId + ",\"toDestinationId\":" + toId + ",\"routeType\":\""
                                + route.toLowerCase() + "\",\"distanceKm\":115.5,\"durationMinutes\":210}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString("/location-distances/")))
                .andExpect(jsonPath("$.routeType").value(route))
                .andExpect(jsonPath("$.distanceKm").value(115.5))
                .andExpect(jsonPath("$.from.name").value("Dist Alpha"))
                .andExpect(jsonPath("$.needsReview").value(false))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        // found from either end, however it is stored
        mvc.perform(get(BASE + "/location-distances/between").param("fromDestinationId", String.valueOf(toId))
                        .param("toDestinationId", String.valueOf(fromId)).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + id + ")]").exists());

        mvc.perform(patch(BASE + "/location-distances/" + id).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"distanceKm\":118,\"durationMinutes\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceKm").value(118.0))
                .andExpect(jsonPath("$.durationMinutes").value(Matchers.nullValue()));

        mvc.perform(get(BASE + "/location-distances").param("routeType", route.toLowerCase()).param("destinationId", String.valueOf(toId))
                        .param("sort", "distanceKm,desc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
        mvc.perform(get(BASE + "/location-distances/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk());

        mvc.perform(delete(BASE + "/location-distances/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/location-distances/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void distanceErrorsComeBackAsProblemJson() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String route = "RT" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        String body = "{\"fromDestinationId\":" + fromId + ",\"toDestinationId\":" + toId + ",\"routeType\":\"" + route
                + "\",\"distanceKm\":50}";

        mvc.perform(post(BASE + "/location-distances").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.fromDestinationId").exists())
                .andExpect(jsonPath("$.fieldErrors.distanceKm").exists());
        mvc.perform(post(BASE + "/location-distances").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDestinationId\":" + fromId + ",\"toDestinationId\":" + fromId + ",\"routeType\":\"ROAD\",\"distanceKm\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.toDestinationId").exists())
                .andExpect(jsonPath("$.fieldErrors.distanceKm").exists());

        mvc.perform(post(BASE + "/location-distances").header(HttpHeaders.AUTHORIZATION, manager)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post(BASE + "/location-distances").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));

        mvc.perform(get(BASE + "/location-distances/between").param("fromDestinationId", "999999").param("toDestinationId", String.valueOf(toId))
                        .header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.fromDestinationId").exists());
        mvc.perform(get(BASE + "/location-distances/between").param("fromDestinationId", String.valueOf(toId))
                        .header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/location-distances").param("sort", "password,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sort").exists());
        mvc.perform(patch(BASE + "/location-distances/999999").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"distanceKm\":5}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/location-distances/999999").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNotFound());
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
