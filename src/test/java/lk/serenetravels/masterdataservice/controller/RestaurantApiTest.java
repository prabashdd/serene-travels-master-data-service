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

// The restaurant endpoints through the real security chain, JSON binding and error mapping. Business rules are
// covered in RestaurantServiceTest; this checks roles, status codes and the shapes the FE sees.
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
class RestaurantApiTest {

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
    private int vegetarianId;
    private int halalId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (jdbc.sql("select count(*) from destination").query(Long.class).single() == 0) {
            jdbc.sql("insert into destination (name, country) values ('Sigiriya', 'Sri Lanka')").update();
        }
        if (jdbc.sql("select count(*) from dietary_category").query(Long.class).single() == 0) {
            jdbc.sql("insert into dietary_category (name) values ('Vegetarian'), ('Halal')").update();
        }
        destinationId = jdbc.sql("select min(id) from destination").query(Integer.class).single();
        vegetarianId = jdbc.sql("select id from dietary_category where name = 'Vegetarian'").query(Integer.class).single();
        halalId = jdbc.sql("select id from dietary_category where name = 'Halal'").query(Integer.class).single();
    }

    @Test
    void agentsCannotReadOrChangeRestaurants() throws Exception {
        String agent = bearer("agent-user", "AGENT");

        mvc.perform(get(BASE + "/restaurants").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/restaurants").header(HttpHeaders.AUTHORIZATION, agent)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/restaurants/1/closed-days").header(HttpHeaders.AUTHORIZATION, agent)
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isForbidden());
    }

    @Test
    void aManagerCanRunRestaurantsEndToEnd() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        String created = mvc.perform(post(BASE + "/restaurants").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kamatha " + suffix + "\",\"destinationId\":" + destinationId
                                + ",\"closedDays\":[\"sunday\",\"Monday\"],\"dietaryCategoryIds\":[" + vegetarianId + "]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString("/restaurants/")))
                .andExpect(jsonPath("$.destination.name").value("Sigiriya"))
                .andExpect(jsonPath("$.closedDays[0]").value("MONDAY"))     // week order, Monday first
                .andExpect(jsonPath("$.closedDays[1]").value("SUNDAY"))
                .andExpect(jsonPath("$.dietary[0].name").value("Vegetarian"))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        mvc.perform(put(BASE + "/restaurants/" + id + "/closed-days").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("[\"Friday\",\"tuesday\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("TUESDAY"))
                .andExpect(jsonPath("$[1]").value("FRIDAY"));

        mvc.perform(put(BASE + "/restaurants/" + id + "/dietary").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("[" + vegetarianId + "," + halalId + "]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(patch(BASE + "/restaurants/" + id).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kamatha Garden " + suffix + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kamatha Garden " + suffix))
                .andExpect(jsonPath("$.closedDays.length()").value(2))
                .andExpect(jsonPath("$.dietary.length()").value(2));

        // filters: closed on Friday finds it; open on Friday does not; sort=name,asc reaches the service whole
        mvc.perform(get(BASE + "/restaurants").param("q", suffix).param("closedOn", "friday").param("sort", "name,asc")
                        .header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
        mvc.perform(get(BASE + "/restaurants").param("q", suffix).param("openOn", "Friday").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(delete(BASE + "/restaurants/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/restaurants/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void restaurantErrorsComeBackAsProblemJson() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String body = "{\"name\":\"Dup " + UUID.randomUUID().toString().substring(0, 6) + "\",\"destinationId\":" + destinationId + "}";

        mvc.perform(post(BASE + "/restaurants").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.destinationId").exists());

        mvc.perform(post(BASE + "/restaurants").header(HttpHeaders.AUTHORIZATION, manager)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post(BASE + "/restaurants").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));

        // a bad day is reported against the offending list item
        mvc.perform(post(BASE + "/restaurants").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"destinationId\":" + destinationId + ",\"closedDays\":[\"Monday\",\"Funday\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['closedDays[1]']").exists());
        mvc.perform(put(BASE + "/restaurants/1/closed-days").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"not\":\"a list\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get(BASE + "/restaurants").param("closedOn", "Someday").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.closedOn").exists());
        mvc.perform(get(BASE + "/restaurants").param("sort", "password,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sort").exists());
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
