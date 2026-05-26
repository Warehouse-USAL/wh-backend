package com.usal.whbackend.config;

import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.jackson.ModelResolver;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;

@Configuration
public class JacksonConfig {

  @Bean
  public JsonMapperBuilderCustomizer jsonCustomizer() {
    return builder -> {
      builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
      builder.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
    };
  }

  /**
   * Gives springdoc a Jackson 2.x ObjectMapper with SNAKE_CASE so that the generated OpenAPI
   * schemas use the same field naming as actual API responses. springdoc-openapi's ModelResolver is
   * built against Jackson 2.x (com.fasterxml), while the app runtime uses Jackson 3.x
   * (tools.jackson), so we construct a dedicated mapper here rather than injecting the app's one.
   */
  @Bean
  public ModelConverter modelConverter() {
    com.fasterxml.jackson.databind.ObjectMapper swaggerMapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    swaggerMapper.setPropertyNamingStrategy(
        com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    return new ModelResolver(swaggerMapper);
  }
}
