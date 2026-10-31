package com.company.resourceapi.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadata of the OpenAPI document at /v3/api-docs (Swagger UI at /swagger-ui.html). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI resourceApiOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Resource API")
                .description("Projects and the SDLC systems they belong to")
                .version("v2"));
    }
}
