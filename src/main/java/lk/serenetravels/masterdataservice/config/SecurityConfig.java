package lk.serenetravels.masterdataservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // Origins allowed to call this API cross-origin - the gateway, whose aggregated Swagger UI
    // fetches /v3/api-docs from this service and calls its endpoints directly.
    @Value("${app.cors.allowed-origins:http://localhost:8080}")
    private String allowedOrigins;

    // Authentication only happens here. WHICH role may call WHAT is enforced per controller with
    // @PreAuthorize (ADMIN/MANAGER on every controller, plus the quotation service account on
    // rate resolution), so a new endpoint is protected by default rather than by a URL list.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        // Boot's error handling forwards unhandled exceptions to /error; without
                        // this that forward is re-evaluated as unauthenticated and masks the real
                        // status behind a 401.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()));
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // The browser must be able to read the ETag to send If-None-Match on /lookups.
        configuration.setExposedHeaders(List.of("ETag", "Location"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // Security-filter-level failures never reach @RestControllerAdvice, so they need their own
    // writers to keep the same application/problem+json shape as every other error.
    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> writeProblem(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "A valid access token is required");
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> writeProblem(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "This service is restricted to ADMIN and MANAGER users");
    }

    private void writeProblem(jakarta.servlet.http.HttpServletResponse response, HttpStatus status, String code,
                              String detail) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        try (PrintWriter writer = response.getWriter()) {
            writer.write("""
                    {"type":"about:blank","title":"%s","status":%d,"code":"%s","detail":"%s"}"""
                    .formatted(status.getReasonPhrase(), status.value(), code, detail));
        }
    }

    // Keycloak puts realm roles under the realm_access.roles claim, not the standard "scope"
    // claim Spring Security maps by default, so they need their own extraction into ROLE_*
    // authorities for method security (same approach as user-service).
    private JwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter defaultConverter = new JwtGrantedAuthoritiesConverter();

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(defaultConverter.convert(jwt));
            authorities.addAll(extractRealmRoles(jwt));
            return authorities;
        });
        return converter;
    }

    private Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
}
