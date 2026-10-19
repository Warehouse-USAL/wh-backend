package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The README tells developers to copy application.yml.example to application.yml, so it must load
 * the way Spring loads YAML (SnakeYAML with duplicate keys rejected).
 */
class ApplicationYmlExampleTest {

  @Test
  void loadsAsSpringYamlWithEverySpringSectionKept() throws Exception {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader()
            .load("example", new ClassPathResource("application.yml.example"));

    assertThat(sources).hasSize(1);
    PropertySource<?> source = sources.get(0);
    assertThat(source.getProperty("spring.application.name")).hasToString("wh-backend");
    assertThat(source.getProperty("spring.servlet.multipart.max-file-size")).hasToString("5MB");
    assertThat(source.getProperty("spring.data.mongodb.auto-index-creation")).isNotNull();
    assertThat(source.getProperty("spring.jackson.property-naming-strategy"))
        .hasToString("SNAKE_CASE");
  }
}
