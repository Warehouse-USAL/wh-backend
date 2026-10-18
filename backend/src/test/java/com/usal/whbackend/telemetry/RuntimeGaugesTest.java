package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RuntimeGaugesTest {

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

    Optional<MetricData> metric(String name) {
      return registration.collectAllMetrics().stream()
          .filter(m -> m.getName().equals(name))
          .findFirst();
    }
  }

  /**
   * Shaped like Tomcat's modeler-built {@code ThreadPool} MBean, whose attribute names are
   * lower-camel ({@code currentThreadsBusy}); a standard MBean would capitalize them.
   */
  private static final class FakeThreadPool implements DynamicMBean {
    private final Map<String, Object> attributes =
        Map.of(
            "currentThreadsBusy", 3,
            "maxThreads", 200,
            "connectionCount", 7L,
            "maxConnections", 8192);

    @Override
    public Object getAttribute(String name) throws AttributeNotFoundException {
      Object value = attributes.get(name);
      if (value == null) {
        throw new AttributeNotFoundException(name);
      }
      return value;
    }

    @Override
    public void setAttribute(Attribute attribute) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AttributeList getAttributes(String[] names) {
      return new AttributeList();
    }

    @Override
    public AttributeList setAttributes(AttributeList list) {
      return new AttributeList();
    }

    @Override
    public Object invoke(String action, Object[] params, String[] signature) {
      throw new UnsupportedOperationException();
    }

    @Override
    public MBeanInfo getMBeanInfo() {
      return new MBeanInfo(FakeThreadPool.class.getName(), "fake", null, null, null, null);
    }
  }

  private final CapturingReader reader = new CapturingReader();
  private final SdkMeterProvider provider =
      SdkMeterProvider.builder().registerMetricReader(reader).build();

  @AfterEach
  void close() {
    provider.close();
  }

  private static List<String> longPoints(MetricData metric) {
    return metric.getLongGaugeData().getPoints().stream()
        .map(p -> p.getAttributes().get(OtelTelemetryAdapter.STATE) + "=" + p.getValue())
        .toList();
  }

  @Test
  void activeRequestsGaugeTracksInFlightAndReturnsToZeroAfterAThrow() {
    HttpMetricsFilter filter = new HttpMetricsFilter(new NoOpTelemetryAdapter());
    new RuntimeGauges(
        provider.get("test"), MBeanServerFactory.newMBeanServer(), filter::activeRequests);
    AtomicReference<Long> duringChain = new AtomicReference<>();
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(ServletRequest req, ServletResponse res) {
            duringChain.set(activeValue());
            throw new IllegalStateException("boom");
          }
        };

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), chain))
        .isInstanceOf(IllegalStateException.class);

    assertThat(duringChain.get()).isEqualTo(1L);
    assertThat(activeValue()).isZero();
  }

  private long activeValue() {
    return reader
        .metric(RuntimeGauges.HTTP_ACTIVE_METRIC)
        .orElseThrow()
        .getLongGaugeData()
        .getPoints()
        .iterator()
        .next()
        .getValue();
  }

  @Test
  void heapGaugeReportsUsedBytes() {
    new RuntimeGauges(provider.get("test"), MBeanServerFactory.newMBeanServer(), () -> 0);

    MetricData heap = reader.metric(RuntimeGauges.HEAP_METRIC).orElseThrow();
    assertThat(heap.getUnit()).isEqualTo("By");
    assertThat(heap.getLongGaugeData().getPoints().iterator().next().getValue()).isPositive();
    MetricData threads = reader.metric(RuntimeGauges.THREADS_METRIC).orElseThrow();
    assertThat(threads.getLongGaugeData().getPoints().iterator().next().getValue()).isPositive();
  }

  @Test
  void gcTimeIsACounterTaggedByCollector() {
    System.gc();
    new RuntimeGauges(provider.get("test"), MBeanServerFactory.newMBeanServer(), () -> 0);

    MetricData gc = reader.metric(RuntimeGauges.GC_METRIC).orElseThrow();
    assertThat(gc.getUnit()).isEqualTo("ms");
    assertThat(gc.getLongSumData().isMonotonic()).isTrue();
    assertThat(gc.getLongSumData().getPoints())
        .isNotEmpty()
        .allSatisfy(p -> assertThat(p.getAttributes().get(RuntimeGauges.GC)).isNotBlank());
  }

  @Test
  void tomcatGaugeIsAbsentWhenMBeanMissing() {
    new RuntimeGauges(provider.get("test"), MBeanServerFactory.newMBeanServer(), () -> 0);

    assertThatCode(() -> reader.metric(RuntimeGauges.TOMCAT_THREADS_METRIC))
        .doesNotThrowAnyException();
    assertThat(reader.metric(RuntimeGauges.TOMCAT_THREADS_METRIC)).isEmpty();
    assertThat(reader.metric(RuntimeGauges.TOMCAT_CONNECTIONS_METRIC)).isEmpty();
  }

  @Test
  void tomcatGaugesReadTheThreadPoolMBean() throws Exception {
    MBeanServer server = MBeanServerFactory.newMBeanServer();
    server.registerMBean(
        new FakeThreadPool(), new ObjectName("Tomcat:type=ThreadPool,name=\"http-nio-8080\""));
    new RuntimeGauges(provider.get("test"), server, () -> 0);

    assertThat(longPoints(reader.metric(RuntimeGauges.TOMCAT_THREADS_METRIC).orElseThrow()))
        .containsExactlyInAnyOrder("busy=3", "max=200");
    assertThat(longPoints(reader.metric(RuntimeGauges.TOMCAT_CONNECTIONS_METRIC).orElseThrow()))
        .containsExactlyInAnyOrder("current=7", "max=8192");
  }

  @Test
  void tomcatGaugeSwallowsMBeanFailures() throws Exception {
    MBeanServer server = MBeanServerFactory.newMBeanServer();
    // Registered under the Tomcat name but lacking every attribute we read.
    server.registerMBean(
        new javax.management.StandardMBean((Runnable) () -> {}, Runnable.class),
        new ObjectName("Tomcat:type=ThreadPool,name=\"broken\""));
    new RuntimeGauges(provider.get("test"), server, () -> 0);

    assertThatCode(() -> reader.metric(RuntimeGauges.TOMCAT_THREADS_METRIC))
        .doesNotThrowAnyException();
    assertThat(reader.metric(RuntimeGauges.TOMCAT_THREADS_METRIC)).isEmpty();
  }

  @Test
  void activeGaugeSwallowsSupplierFailures() {
    new RuntimeGauges(
        provider.get("test"),
        ManagementFactory.getPlatformMBeanServer(),
        () -> {
          throw new IllegalStateException("boom");
        });

    assertThatCode(() -> reader.metric(RuntimeGauges.HTTP_ACTIVE_METRIC))
        .doesNotThrowAnyException();
  }

  @Test
  void wsSessionDeltaMovesGauge() {
    OtelTelemetryAdapter adapter = new OtelTelemetryAdapter(provider.get("test"), EMPTY_FLEET);

    adapter.recordWsSession("orders", 1);
    adapter.recordWsSession("orders", 1);
    adapter.recordWsSession("vehicles", 1);
    adapter.recordWsSession("vehicles", -1);

    MetricData sessions = reader.metric(OtelTelemetryAdapter.WS_SESSIONS_METRIC).orElseThrow();
    assertThat(sessions.getLongGaugeData().getPoints())
        .extracting(p -> p.getAttributes().get(OtelTelemetryAdapter.FEED) + "=" + p.getValue())
        .containsExactlyInAnyOrder("orders=2", "vehicles=0");
  }

  @Test
  void wsMessagesAndKafkaMessagesAreCountedWithTheirTags() {
    OtelTelemetryAdapter adapter = new OtelTelemetryAdapter(provider.get("test"), EMPTY_FLEET);

    adapter.recordWsMessage("orders", "pushed");
    adapter.recordWsMessage("orders", "pushed");
    adapter.recordKafkaMessage("orders", "out", true);
    adapter.recordKafkaMessage("orders", "out", false);

    assertThat(
            reader
                .metric(OtelTelemetryAdapter.WS_MESSAGES_METRIC)
                .orElseThrow()
                .getLongSumData()
                .getPoints())
        .extracting(
            p ->
                p.getAttributes().get(OtelTelemetryAdapter.FEED)
                    + "/"
                    + p.getAttributes().get(OtelTelemetryAdapter.RESULT)
                    + "="
                    + p.getValue())
        .containsExactly("orders/pushed=2");
    assertThat(
            reader
                .metric(OtelTelemetryAdapter.KAFKA_METRIC)
                .orElseThrow()
                .getLongSumData()
                .getPoints())
        .extracting(
            p ->
                p.getAttributes().get(OtelTelemetryAdapter.TOPIC)
                    + "/"
                    + p.getAttributes().get(OtelTelemetryAdapter.DIRECTION)
                    + "/"
                    + p.getAttributes().get(OtelTelemetryAdapter.RESULT)
                    + "="
                    + p.getValue())
        .containsExactlyInAnyOrder("orders/out/ok=1", "orders/out/error=1");
  }
}
