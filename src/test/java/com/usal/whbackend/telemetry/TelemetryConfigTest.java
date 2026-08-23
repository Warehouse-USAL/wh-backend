package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;

class TelemetryConfigTest {

  private final TelemetryConfig config = new TelemetryConfig();

  @Test
  void usesNoOpAdapterWhenTelemetryIsDisabled() {
    TelemetryProperties props = new TelemetryProperties();
    props.setEnabled(false);

    OpenTelemetrySdk sdk = config.openTelemetrySdk(props);

    assertThat(config.telemetryPort(sdk, props)).isInstanceOf(NoOpTelemetryAdapter.class);
    sdk.close();
  }

  @Test
  void usesOtelAdapterWhenTelemetryIsEnabled() {
    TelemetryProperties props = new TelemetryProperties();
    props.setEnabled(true);
    props.setEndpoint("http://localhost:4318/v1/metrics");

    OpenTelemetrySdk sdk = config.openTelemetrySdk(props);

    assertThat(config.telemetryPort(sdk, props)).isInstanceOf(OtelTelemetryAdapter.class);
    sdk.close();
  }

  @Test
  void telemetryIsDisabledByDefault() {
    assertThat(new TelemetryProperties().isEnabled()).isFalse();
  }
}
