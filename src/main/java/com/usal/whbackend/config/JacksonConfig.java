package com.usal.whbackend.config;

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
}
