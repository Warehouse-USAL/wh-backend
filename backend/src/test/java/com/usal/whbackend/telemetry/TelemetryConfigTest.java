package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelemetryConfigTest {

  private final TelemetryConfig config = new TelemetryConfig();

  /** An empty fleet is enough: these tests assert which adapter is chosen, not what it observes. */
  private static final FleetStateSource EMPTY_FLEET =
      new FleetStateSource() {
        @Override
        public List<VehicleState> currentFleet() {
          return List.of();
        }

        @Override
        public List<String> knownStates() {
          return List.of();
        }
      };

  @Test
  void usesNoOpAdapterWhenTelemetryIsDisabled() {
    TelemetryProperties props = new TelemetryProperties();
    props.setEnabled(false);

    OpenTelemetrySdk sdk = config.openTelemetrySdk(props);

    assertThat(config.telemetryPort(sdk, props, EMPTY_FLEET))
        .isInstanceOf(NoOpTelemetryAdapter.class);
    sdk.close();
  }

  @Test
  void usesOtelAdapterWhenTelemetryIsEnabled() {
    TelemetryProperties props = new TelemetryProperties();
    props.setEnabled(true);
    props.setEndpoint("http://localhost:4318/v1/metrics");

    OpenTelemetrySdk sdk = config.openTelemetrySdk(props);

    assertThat(config.telemetryPort(sdk, props, EMPTY_FLEET))
        .isInstanceOf(OtelTelemetryAdapter.class);
    sdk.close();
  }

  @Test
  void telemetryIsDisabledByDefault() {
    assertThat(new TelemetryProperties().isEnabled()).isFalse();
  }
}
