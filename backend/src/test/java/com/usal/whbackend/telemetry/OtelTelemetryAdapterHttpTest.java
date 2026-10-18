package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class OtelTelemetryAdapterHttpTest {

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

  /** Pull-based reader so the test can read what the SDK aggregated, no exporter involved. */
  private static final class CapturingReader implements MetricReader {
    private CollectionRegistration registration;

    @Override
    public void register(CollectionRegistration registration) {
      this.registration = registration;
    }

    @Override
    public CompletableResultCode forceFlush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
      return AggregationTemporality.CUMULATIVE;
    }

    Collection<MetricData> collect() {
      return registration.collectAllMetrics();
    }
  }

  @Test
  void statusClassTagIsDerivedFromStatus() {
    CapturingReader reader = new CapturingReader();
    SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    OtelTelemetryAdapter adapter = new OtelTelemetryAdapter(provider.get("test"), EMPTY_FLEET);

    adapter.recordHttpRequest(new HttpRequestSample("/orders/{id}", "GET", 503, 12.0));

    MetricData metric =
        reader.collect().stream()
            .filter(m -> m.getName().equals(OtelTelemetryAdapter.HTTP_METRIC))
            .findFirst()
            .orElseThrow();
    assertThat(metric.getUnit()).isEqualTo("ms");
    HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
    assertThat(point.getAttributes().get(OtelTelemetryAdapter.STATUS_CLASS)).isEqualTo("5xx");
    assertThat(point.getAttributes().get(OtelTelemetryAdapter.ROUTE)).isEqualTo("/orders/{id}");
    assertThat(point.getAttributes().get(OtelTelemetryAdapter.METHOD)).isEqualTo("GET");
    assertThat(point.getBoundaries())
        .containsExactly(
            5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0);
    provider.close();
  }

  @Test
  void adapterNeverThrows() {
    Meter meter = mock(Meter.class, RETURNS_DEEP_STUBS);
    DoubleHistogram histogram = mock(DoubleHistogram.class);
    when(meter
            .histogramBuilder(anyString())
            .setUnit(anyString())
            .setDescription(anyString())
            .setExplicitBucketBoundariesAdvice(any())
            .build())
        .thenReturn(histogram);
    doThrow(new IllegalStateException("collector down")).when(histogram).record(anyDouble(), any());
    OtelTelemetryAdapter adapter = new OtelTelemetryAdapter(meter, EMPTY_FLEET);

    assertThatCode(() -> adapter.recordHttpRequest(new HttpRequestSample("/x", "GET", 200, 1.0)))
        .doesNotThrowAnyException();
    assertThatCode(() -> adapter.recordHttpRequest(null)).doesNotThrowAnyException();
  }
}
