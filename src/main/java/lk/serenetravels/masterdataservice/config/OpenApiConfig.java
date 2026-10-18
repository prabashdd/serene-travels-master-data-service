package lk.serenetravels.masterdataservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI masterDataOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Master Data Service API")
                        .description("Internal back-office API (ADMIN / MANAGER only) for reference lookups and "
                                + "the versioned rate engine. The planned design is in docs/API-DESIGN.md and "
                                + "docs/openapi.yaml. Paste a Keycloak access token into the bearerAuth field.")
                        .version("v1"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
