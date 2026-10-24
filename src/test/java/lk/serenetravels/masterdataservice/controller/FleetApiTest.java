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
import java.time.LocalDate;
import java.time.ZoneId;
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

// The vehicle and driver endpoints through the real security chain, JSON binding and error mapping. Business
// rules are covered in VehicleServiceTest / DriverServiceTest; this checks roles, status codes and the shapes
// the FE sees.
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
class FleetApiTest {

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
    private int carTypeId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (jdbc.sql("select count(*) from vehicle_type").query(Long.class).single() == 0) {
            jdbc.sql("insert into vehicle_type (name, min_capacity, max_capacity) values ('Car', 1, 2), ('Van', 3, 9)").update();
        }
        carTypeId = jdbc.sql("select min(id) from vehicle_type").query(Integer.class).single();
    }

    @Test
    void agentsCannotReadOrChangeTheFleet() throws Exception {
        String agent = bearer("agent-user", "AGENT");

        mvc.perform(get(BASE + "/vehicles").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/drivers").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/vehicles").header(HttpHeaders.AUTHORIZATION, agent)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/drivers/1").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
    }

    @Test
    void aManagerCanRunTheFleetEndToEnd() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));

        // a driver and an assistant
        String driver = mvc.perform(post(BASE + "/drivers").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nuwan " + suffix + "\",\"nic\":\"N" + suffix + "\",\"isChauffeur\":true}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString("/drivers/")))
                .andExpect(jsonPath("$.role").value("DRIVER"))
                .andExpect(jsonPath("$.isChauffeur").value(true))
                .andReturn().getResponse().getContentAsString();
        int driverId = JsonPath.read(driver, "$.id");
        String assistant = mvc.perform(post(BASE + "/drivers").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kamal " + suffix + "\",\"nic\":\"K" + suffix + "\",\"role\":\"assistant\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ASSISTANT"))
                .andReturn().getResponse().getContentAsString();
        int assistantId = JsonPath.read(assistant, "$.id");

        // a vehicle with an insurance date already past
        String created = mvc.perform(post(BASE + "/vehicles").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNo\":\"t" + suffix + "\",\"vehicleTypeId\":" + carTypeId
                                + ",\"seatingCapacity\":2,\"method\":\"CONTRACTED\",\"driverId\":" + driverId
                                + ",\"assistantId\":" + assistantId + ",\"insuranceExpiryDate\":\"" + today.minusDays(5)
                                + "\",\"revenueLicenseExpiryDate\":\"" + today.plusYears(1) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString("/vehicles/")))
                .andExpect(jsonPath("$.registrationNo").value("T" + suffix.toUpperCase()))
                .andExpect(jsonPath("$.driver.name").value("Nuwan " + suffix))
                .andExpect(jsonPath("$.assistant.name").value("Kamal " + suffix))
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0].code").value("INSURANCE_EXPIRED"))
                .andExpect(jsonPath("$.warnings[0].daysLeft").value(-5))
                .andReturn().getResponse().getContentAsString();
        int vehicleId = JsonPath.read(created, "$.id");

        // the driver now lists the vehicle they are assigned to
        mvc.perform(get(BASE + "/drivers/" + driverId).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicles.length()").value(1))
                .andExpect(jsonPath("$.vehicles[0].id").value(vehicleId));

        // the expiring-documents filter finds it, and sort=registrationNo,asc reaches the service whole
        mvc.perform(get(BASE + "/vehicles").param("q", suffix).param("expiringWithinDays", "30")
                        .param("sort", "registrationNo,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(vehicleId));

        // renew the insurance and drop the assistant in one quick edit
        mvc.perform(patch(BASE + "/vehicles/" + vehicleId).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insuranceExpiryDate\":\"" + today.plusYears(1) + "\",\"assistantId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings.length()").value(0))
                .andExpect(jsonPath("$.assistant").doesNotExist())
                .andExpect(jsonPath("$.driver.id").value(driverId));

        // deactivating the driver flags the vehicle that still depends on them
        mvc.perform(delete(BASE + "/drivers/" + driverId).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/vehicles/" + vehicleId).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.warnings[0].code").value("DRIVER_INACTIVE"));

        mvc.perform(delete(BASE + "/vehicles/" + vehicleId).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/vehicles/" + vehicleId).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.warnings.length()").value(0));
    }

    @Test
    void fleetErrorsComeBackAsProblemJson() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        mvc.perform(post(BASE + "/vehicles").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.registrationNo").exists())
                .andExpect(jsonPath("$.fieldErrors.vehicleTypeId").exists())
                .andExpect(jsonPath("$.fieldErrors.seatingCapacity").exists())
                .andExpect(jsonPath("$.fieldErrors.method").exists());

        // a Car carries two people at most
        mvc.perform(post(BASE + "/vehicles").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNo\":\"e" + suffix + "\",\"vehicleTypeId\":" + carTypeId
                                + ",\"seatingCapacity\":6,\"method\":\"INTERNAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.seatingCapacity").exists());

        String body = "{\"registrationNo\":\"d" + suffix + "\",\"vehicleTypeId\":" + carTypeId
                + ",\"seatingCapacity\":2,\"method\":\"INTERNAL\"}";
        mvc.perform(post(BASE + "/vehicles").header(HttpHeaders.AUTHORIZATION, manager)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post(BASE + "/vehicles").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));

        String person = "{\"name\":\"Dup\",\"nic\":\"D" + suffix + "\"}";
        mvc.perform(post(BASE + "/drivers").header(HttpHeaders.AUTHORIZATION, manager)
                .contentType(MediaType.APPLICATION_JSON).content(person)).andExpect(status().isCreated());
        mvc.perform(post(BASE + "/drivers").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content(person))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));

        mvc.perform(get(BASE + "/vehicles").param("sort", "password,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sort").exists());
        mvc.perform(get(BASE + "/drivers").param("role", "BOSS").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.role").exists());
        mvc.perform(get(BASE + "/vehicles/987654").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aRoleCannotChangeUnderAVehicleThatUsesIt() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String driver = mvc.perform(post(BASE + "/drivers").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Roles " + suffix + "\",\"nic\":\"R" + suffix + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int driverId = JsonPath.read(driver, "$.id");
        mvc.perform(post(BASE + "/vehicles").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNo\":\"r" + suffix + "\",\"vehicleTypeId\":" + carTypeId
                                + ",\"seatingCapacity\":2,\"method\":\"INTERNAL\",\"driverId\":" + driverId + "}"))
                .andExpect(status().isCreated());

        mvc.perform(patch(BASE + "/drivers/" + driverId).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ASSISTANT\"}"))
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
