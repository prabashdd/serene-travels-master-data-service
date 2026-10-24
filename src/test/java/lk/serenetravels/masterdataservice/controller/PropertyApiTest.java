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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The hotel endpoints through the real security chain, JSON binding and error mapping. Business rules are
// covered in PropertyServiceTest; this checks roles, status codes, headers and the shapes the FE sees.
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
class PropertyApiTest {

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
            jdbc.sql("insert into destination (name, country) values ('Habarana', 'Sri Lanka')").update();
        }
        destinationId = jdbc.sql("select min(id) from destination").query(Integer.class).single();
    }

    @Test
    void agentsCannotReadOrChangeHotels() throws Exception {
        String agent = bearer("agent-user", "AGENT");

        mvc.perform(get(BASE + "/properties").header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/properties").header(HttpHeaders.AUTHORIZATION, agent)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/room-types/1").header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());
    }

    @Test
    void aManagerCanCreateReadEditListAndDeactivateAHotel() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String code = uniqueCode("T");

        String created = mvc.perform(post(BASE + "/properties").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", code, "name", "Test Lodge " + code, "destinationId", destinationId, "starRating", 4)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString("/properties/")))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.destination.name").value("Habarana"))
                .andExpect(jsonPath("$.roomTypes.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        mvc.perform(get(BASE + "/properties/" + id).param("include", "rates").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates.length()").value(0));

        mvc.perform(patch(BASE + "/properties/" + id).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed " + code + "\",\"address\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed " + code))
                .andExpect(jsonPath("$.starRating").value(4));

        // sort=name,asc must reach the service whole, not split on the comma into two values
        mvc.perform(get(BASE + "/properties").param("q", code).param("sort", "name,asc").param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value(code))
                .andExpect(jsonPath("$.content[0].roomTypeCount").value(0))
                .andExpect(jsonPath("$.content[0].destination.name").value("Habarana"));

        mvc.perform(delete(BASE + "/properties/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/properties/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void hotelErrorsComeBackAsProblemJson() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String body = json("code", uniqueCode("E"), "name", "Dup", "destinationId", destinationId);

        mvc.perform(post(BASE + "/properties").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.fieldErrors.code").exists())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.destinationId").exists());

        mvc.perform(post(BASE + "/properties").header(HttpHeaders.AUTHORIZATION, manager)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post(BASE + "/properties").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));

        mvc.perform(get(BASE + "/properties").param("sort", "password,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sort").exists());
        mvc.perform(get(BASE + "/properties/1").param("include", "bogus").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.include").exists());
        mvc.perform(get(BASE + "/properties/987654").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void roomTypesChildRulesAndBabyCotWorkOverHttp() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String created = mvc.perform(post(BASE + "/properties").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", uniqueCode("R"), "name", "Rooms Lodge", "destinationId", destinationId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        String room = mvc.perform(post(BASE + "/properties/" + id + "/room-types").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Deluxe\",\"baseCapacity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.name").value("Deluxe"))
                .andReturn().getResponse().getContentAsString();
        int roomId = JsonPath.read(room, "$.id");

        mvc.perform(patch(BASE + "/room-types/" + roomId).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"extraBedAllowed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extraBedAllowed").value(true))
                .andExpect(jsonPath("$.baseCapacity").value(2));

        // Overlapping age bands are reported against the offending item.
        mvc.perform(put(BASE + "/properties/" + id + "/child-rules").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"minAge\":0,\"maxAge\":6,\"requiresBed\":false,\"cost\":0},"
                                + "{\"minAge\":5,\"maxAge\":11,\"requiresBed\":true,\"cost\":20}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors['[1].minAge']").exists());

        mvc.perform(put(BASE + "/properties/" + id + "/child-rules").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"minAge\":0,\"maxAge\":5,\"requiresBed\":false,\"cost\":0},"
                                + "{\"minAge\":6,\"maxAge\":11,\"requiresBed\":true,\"cost\":20}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].cost").value(20.0));

        mvc.perform(put(BASE + "/properties/" + id + "/baby-cot").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"available\":true,\"cost\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));

        mvc.perform(delete(BASE + "/room-types/" + roomId).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/properties/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.roomTypes.length()").value(0))
                .andExpect(jsonPath("$.childRules.length()").value(2))
                .andExpect(jsonPath("$.babyCot.cost").value(10.0));
    }

    // ------------------------------------------------------------------ helpers

    private static String uniqueCode(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 7);
    }

    // {"k":v,...} from key/value pairs; strings are quoted, everything else is written as-is.
    private static String json(Object... pairs) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(pairs[i]).append("\":");
            Object value = pairs[i + 1];
            out.append(value instanceof String ? "\"" + value + "\"" : value);
        }
        return out.append('}').toString();
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
