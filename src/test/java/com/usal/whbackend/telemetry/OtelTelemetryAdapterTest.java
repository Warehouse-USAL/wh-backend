package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.Meter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtelTelemetryAdapterTest {

  private DoubleGauge gauge;
  private OtelTelemetryAdapter adapter;

  @BeforeEach
  void setUp() {
    Meter meter = mock(Meter.class, RETURNS_DEEP_STUBS);
    gauge = mock(DoubleGauge.class);
    when(meter.gaugeBuilder(anyString()).setUnit(anyString()).setDescription(anyString()).build())
        .thenReturn(gauge);
    adapter = new OtelTelemetryAdapter(meter);
  }

  @Test
  void recordsBatteryWithVehicleIdAttribute() {
    adapter.recordVehicleSample(new VehicleSample("VHC-001", 79));

    verify(gauge).set(eq(79.0), eq(Attributes.of(OtelTelemetryAdapter.VEHICLE_ID, "VHC-001")));
  }

  @Test
  void swallowsExporterFailureSoTelemetryNeverBreaksTheCaller() {
    org.mockito.Mockito.doThrow(new IllegalStateException("collector down"))
        .when(gauge)
        .set(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.any());

    assertThatCode(() -> adapter.recordVehicleSample(new VehicleSample("VHC-001", 50)))
        .doesNotThrowAnyException();
  }

  @Test
  void ignoresSamplesWithNoVehicleId() {
    adapter.recordVehicleSample(null);
    adapter.recordVehicleSample(new VehicleSample(null, 50));
    adapter.recordVehicleSample(new VehicleSample("  ", 50));

    verify(gauge, never())
        .set(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void noOpAdapterDiscardsSamplesSilently() {
    assertThatCode(() -> new NoOpTelemetryAdapter().recordVehicleSample(new VehicleSample("V", 1)))
        .doesNotThrowAnyException();
  }
}
