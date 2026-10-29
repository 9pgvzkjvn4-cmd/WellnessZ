package com.wellnessz.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** OpenAPI 3 contract (published at /v3/api-docs; Swagger UI disabled in production profiles). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI wellnessZOpenApi() {
        return new OpenAPI()
                .info(new Info().title("WellnessZ OHCM Core API").version("v1")
                        .description("Airline Occupational Health Case Management – platform services and case kernel"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
