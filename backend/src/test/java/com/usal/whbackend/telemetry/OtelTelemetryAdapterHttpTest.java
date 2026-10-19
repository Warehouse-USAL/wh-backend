package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
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
  void mongoCommandAndCheckoutAreRecordedAsHistograms() {
    CapturingReader reader = new CapturingReader();
    SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    OtelTelemetryAdapter adapter = new OtelTelemetryAdapter(provider.get("test"), EMPTY_FLEET);

    adapter.recordMongoCommand(new MongoCommandSample("orders", "find", "slow", 250.0));
    adapter.recordMongoCheckout(3.0);

    MetricData command = metric(reader, OtelTelemetryAdapter.MONGO_COMMAND_METRIC);
    HistogramPointData point = command.getHistogramData().getPoints().iterator().next();
    assertThat(command.getUnit()).isEqualTo("ms");
    assertThat(point.getAttributes().get(OtelTelemetryAdapter.COLLECTION)).isEqualTo("orders");
    assertThat(point.getAttributes().get(OtelTelemetryAdapter.COMMAND)).isEqualTo("find");
    assertThat(point.getAttributes().get(OtelTelemetryAdapter.OUTCOME)).isEqualTo("slow");
    MetricData checkout = metric(reader, OtelTelemetryAdapter.MONGO_CHECKOUT_METRIC);
    assertThat(checkout.getHistogramData().getPoints().iterator().next().getCount()).isEqualTo(1);
    provider.close();
  }

  @Test
  void failedCheckoutsAreCountedWithABoundedReason() {
    CapturingReader reader = new CapturingReader();
    SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    OtelTelemetryAdapter adapter = new OtelTelemetryAdapter(provider.get("test"), EMPTY_FLEET);

    adapter.recordMongoCheckoutFailed("timeout");
    adapter.recordMongoCheckoutFailed("timeout");
    adapter.recordMongoCheckoutFailed("something-new");
    adapter.recordMongoCheckoutFailed(null);

    MetricData failed = metric(reader, OtelTelemetryAdapter.MONGO_CHECKOUT_FAILED_METRIC);
    assertThat(failed.getLongSumData().getPoints())
        .extracting(p -> p.getAttributes().get(OtelTelemetryAdapter.REASON) + "=" + p.getValue())
        .containsExactlyInAnyOrder("timeout=2", "unknown=2");
    provider.close();
  }

  @Test
  void kafkaPublishLatencyIsTaggedWithAKnownTopicAndResult() {
    CapturingReader reader = new CapturingReader();
    SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    OtelTelemetryAdapter adapter = new OtelTelemetryAdapter(provider.get("test"), EMPTY_FLEET);

    adapter.recordKafkaPublish("orders", true, 12.0);
    adapter.recordKafkaPublish("order.completed", false, 30.0);
    adapter.recordKafkaPublish("orders.v2", true, 1.0);
    adapter.recordKafkaPublish(null, true, 1.0);

    MetricData publish = metric(reader, OtelTelemetryAdapter.KAFKA_PUBLISH_METRIC);
    assertThat(publish.getUnit()).isEqualTo("ms");
    assertThat(publish.getHistogramData().getPoints())
        .extracting(
            p ->
                p.getAttributes().get(OtelTelemetryAdapter.TOPIC)
                    + "/"
                    + p.getAttributes().get(OtelTelemetryAdapter.RESULT)
                    + "="
                    + p.getCount())
        .containsExactlyInAnyOrder("orders/ok=1", "order.completed/error=1", "other/ok=2");
    provider.close();
  }

  @Test
  void poolGaugeReportsInUseAndAvailable() {
    CapturingReader reader = new CapturingReader();
    SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    OtelTelemetryAdapter adapter = new OtelTelemetryAdapter(provider.get("test"), EMPTY_FLEET);

    adapter.registerPoolGauge(() -> new MongoMetricsListener.PoolStats(2, 5));

    MetricData gauge = metric(reader, OtelTelemetryAdapter.MONGO_POOL_METRIC);
    assertThat(gauge.getDoubleGaugeData().getPoints())
        .extracting(p -> p.getAttributes().get(OtelTelemetryAdapter.STATE) + "=" + p.getValue())
        .containsExactlyInAnyOrder("in_use=2.0", "available=5.0");
    provider.close();
  }

  @Test
  void poolGaugeCallbackSwallowsFailures() {
    CapturingReader reader = new CapturingReader();
    SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    OtelTelemetryAdapter adapter = new OtelTelemetryAdapter(provider.get("test"), EMPTY_FLEET);
    adapter.registerPoolGauge(
        () -> {
          throw new IllegalStateException("boom");
        });

    assertThatCode(reader::collect).doesNotThrowAnyException();
    provider.close();
  }

  @Test
  void mongoRecordingNeverThrows() {
    Meter meter = mock(Meter.class, RETURNS_DEEP_STUBS);
    DoubleHistogram histogram = mock(DoubleHistogram.class);
    when(meter
            .histogramBuilder(anyString())
            .setUnit(anyString())
            .setDescription(anyString())
            .setExplicitBucketBoundariesAdvice(any())
            .build())
        .thenReturn(histogram);
    doThrow(new IllegalStateException("down")).when(histogram).record(anyDouble(), any());
    doThrow(new IllegalStateException("down")).when(histogram).record(anyDouble());
    OtelTelemetryAdapter adapter = new OtelTelemetryAdapter(meter, EMPTY_FLEET);

    assertThatCode(() -> adapter.recordMongoCommand(new MongoCommandSample("c", "find", "ok", 1)))
        .doesNotThrowAnyException();
    assertThatCode(() -> adapter.recordMongoCommand(null)).doesNotThrowAnyException();
    assertThatCode(() -> adapter.recordMongoCheckout(1.0)).doesNotThrowAnyException();
    assertThatCode(() -> adapter.recordKafkaPublish("orders", true, 1.0))
        .doesNotThrowAnyException();
  }

  @Test
  void failedCheckoutCountingNeverThrows() {
    Meter meter = mock(Meter.class, RETURNS_DEEP_STUBS);
    LongCounter counter = mock(LongCounter.class);
    when(meter.counterBuilder(anyString()).setUnit(anyString()).setDescription(anyString()).build())
        .thenReturn(counter);
    doThrow(new IllegalStateException("down")).when(counter).add(anyLong(), any());
    OtelTelemetryAdapter adapter = new OtelTelemetryAdapter(meter, EMPTY_FLEET);

    assertThatCode(() -> adapter.recordMongoCheckoutFailed("timeout")).doesNotThrowAnyException();
  }

  private static MetricData metric(CapturingReader reader, String name) {
    return reader.collect().stream()
        .filter(m -> m.getName().equals(name))
        .findFirst()
        .orElseThrow();
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
