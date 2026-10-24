package com.usal.whbackend.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.function.IntSupplier;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observable gauges for the process itself: heap, threads, GC time, Tomcat's request thread pool
 * and connections, and requests in flight. All are polled once per export interval; every callback
 * swallows its own failures so one broken source never takes the export down.
 *
 * <p>Tomcat's numbers come from its {@code ThreadPool} MBean, which only exists when {@code
 * server.tomcat.mbeanregistry.enabled=true}. Without it the Tomcat series are simply absent.
 */
public class RuntimeGauges {

  private static final Logger log = LoggerFactory.getLogger(RuntimeGauges.class);

  public static final String HEAP_METRIC = "jvm.heap.used";
  public static final String THREADS_METRIC = "jvm.threads";
  public static final String GC_METRIC = "jvm.gc.time";
  public static final String TOMCAT_THREADS_METRIC = "tomcat.threads";
  public static final String TOMCAT_CONNECTIONS_METRIC = "tomcat.connections";
  public static final String HTTP_ACTIVE_METRIC = "http.server.active";

  static final AttributeKey<String> GC = AttributeKey.stringKey("gc");

  private static final String THREAD_POOLS = "Tomcat:type=ThreadPool,name=*";

  public RuntimeGauges(Meter meter, MBeanServer mbeans, IntSupplier activeRequests) {
    meter
        .gaugeBuilder(HEAP_METRIC)
        .ofLongs()
        .setUnit("By")
        .setDescription("JVM heap used")
        .buildWithCallback(
            m ->
                observe(
                    "heap",
                    () ->
                        m.record(
                            ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed())));

    meter
        .gaugeBuilder(THREADS_METRIC)
        .ofLongs()
        .setUnit("1")
        .setDescription("JVM live threads")
        .buildWithCallback(
            m ->
                observe(
                    "threads",
                    () -> m.record(ManagementFactory.getThreadMXBean().getThreadCount())));

    meter
        .counterBuilder(GC_METRIC)
        .setUnit("ms")
        .setDescription("Accumulated JVM garbage collection time by collector")
        .buildWithCallback(m -> observe("gc", () -> recordGc(m)));

    meter
        .gaugeBuilder(TOMCAT_THREADS_METRIC)
        .ofLongs()
        .setUnit("1")
        .setDescription("Tomcat request threads, busy versus max")
        .buildWithCallback(
            m ->
                observe(
                    "tomcat threads",
                    () ->
                        recordPool(mbeans, m, "currentThreadsBusy", "busy", "maxThreads", "max")));

    meter
        .gaugeBuilder(TOMCAT_CONNECTIONS_METRIC)
        .ofLongs()
        .setUnit("1")
        .setDescription("Tomcat connections, current versus max")
        .buildWithCallback(
            m ->
                observe(
                    "tomcat connections",
                    () ->
                        recordPool(
                            mbeans, m, "connectionCount", "current", "maxConnections", "max")));

    meter
        .gaugeBuilder(HTTP_ACTIVE_METRIC)
        .ofLongs()
        .setUnit("1")
        .setDescription("HTTP requests in flight")
        .buildWithCallback(
            m -> observe("active requests", () -> m.record(activeRequests.getAsInt())));
  }

  private static void recordGc(ObservableLongMeasurement m) {
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      long ms = gc.getCollectionTime();
      if (ms >= 0) {
        m.record(ms, Attributes.of(GC, gc.getName()));
      }
    }
  }

  /**
   * Sums two attributes over every Tomcat connector; records nothing when there is none. A negative
   * limit is Tomcat's "unlimited" ({@code maxConnections=-1}): the limit is then left out rather
   * than summed into a meaningless number.
   */
  private static void recordPool(
      MBeanServer mbeans,
      ObservableLongMeasurement m,
      String first,
      String firstState,
      String second,
      String secondState) {
    long a = 0;
    long b = 0;
    boolean unlimited = false;
    try {
      Set<ObjectName> names = mbeans.queryNames(new ObjectName(THREAD_POOLS), null);
      if (names.isEmpty()) {
        return;
      }
      for (ObjectName name : names) {
        a += ((Number) mbeans.getAttribute(name, first)).longValue();
        long limit = ((Number) mbeans.getAttribute(name, second)).longValue();
        unlimited |= limit < 0;
        b += limit;
      }
    } catch (JMException e) {
      log.warn("Failed to read Tomcat MBean: {}", e.getMessage());
      return;
    }
    m.record(a, Attributes.of(OtelTelemetryAdapter.STATE, firstState));
    if (!unlimited) {
      m.record(b, Attributes.of(OtelTelemetryAdapter.STATE, secondState));
    }
  }

  /** Runs on the SDK's export thread: throwing there would kill every other metric's export. */
  private static void observe(String what, Runnable callback) {
    try {
      callback.run();
    } catch (RuntimeException e) {
      log.warn("Failed to observe {}: {}", what, e.getMessage());
    }
  }
}
