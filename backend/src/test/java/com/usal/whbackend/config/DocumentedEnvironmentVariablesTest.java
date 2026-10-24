package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.service.console.ConsoleProperties;
import com.usal.whbackend.telemetry.TelemetryProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The prod overlay has no profile YAML: the variables docs/CONSOLE.md documents (and the compose
 * files set) reach the properties only through Spring's environment-variable binding.
 */
class DocumentedEnvironmentVariablesTest {

  private static Binder binderFor(Map<String, Object> variables) {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
    return new Binder(ConfigurationPropertySources.get(environment));
  }

  @Test
  void telemetryVariablesBind() {
    TelemetryProperties properties = new TelemetryProperties();

    binderFor(
            Map.of(
                "TELEMETRY_ENABLED", "true",
                "TELEMETRY_ENDPOINT", "http://c:4318/v1/metrics",
                "TELEMETRY_LOGS_ENDPOINT", "http://c:4318/v1/logs"))
        .bind("telemetry", Bindable.ofInstance(properties));

    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.getEndpoint()).isEqualTo("http://c:4318/v1/metrics");
    assertThat(properties.getLogsEndpoint()).isEqualTo("http://c:4318/v1/logs");
  }

  @Test
  void consoleVariablesBind() {
    ConsoleProperties properties = new ConsoleProperties();

    binderFor(
            Map.of(
                "CONSOLE_VICTORIALOGS_URL", "http://vl:1",
                "CONSOLE_BRIDGE_URL", "http://br:2",
                "CONSOLE_MINIO_URL", "http://mi:3",
                "CONSOLE_VICTORIAMETRICS_URL", "http://vm:4",
                "CONSOLE_COLLECTOR_URL", "http://co:5"))
        .bind("console", Bindable.ofInstance(properties));

    assertThat(properties.getVictorialogsUrl()).isEqualTo("http://vl:1");
    assertThat(properties.getBridgeUrl()).isEqualTo("http://br:2");
    assertThat(properties.getMinioUrl()).isEqualTo("http://mi:3");
    assertThat(properties.getVictoriametricsUrl()).isEqualTo("http://vm:4");
    assertThat(properties.getCollectorUrl()).isEqualTo("http://co:5");
  }
}
