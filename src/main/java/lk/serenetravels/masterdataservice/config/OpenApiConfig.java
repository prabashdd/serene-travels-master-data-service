package lk.serenetravels.masterdataservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;

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

    // The list endpoints read `sort` from the raw request (a bound List<String> would split "name,asc" on the comma),
    // so it is invisible to springdoc. A method that takes the request and describes it with @Parameter gets a
    // documented, repeatable `sort` query parameter - in Swagger UI and in the generated API reference.
    @Bean
    public OperationCustomizer sortParameter() {
        return (operation, handlerMethod) -> {
            for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
                io.swagger.v3.oas.annotations.Parameter documented =
                        parameter.getParameterAnnotation(io.swagger.v3.oas.annotations.Parameter.class);
                if (HttpServletRequest.class.equals(parameter.getParameterType()) && documented != null) {
                    operation.addParametersItem(new QueryParameter()
                            .name("sort")
                            .description(documented.description())
                            .required(false)
                            .explode(true)
                            .schema(new ArraySchema().items(new StringSchema())));
                }
            }
            return operation;
        };
    }
}
