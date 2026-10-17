package com.usal.whbackend.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI smartWarehouseOpenAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("SmartWarehouse API")
                .description("Backend API for the SmartWarehouse autonomous warehouse system")
                .version("v1.0"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearer-jwt",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT token obtained from POST /auth/login")));
  }
}
