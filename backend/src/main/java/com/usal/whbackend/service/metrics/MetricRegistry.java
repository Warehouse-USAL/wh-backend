package com.usal.whbackend.service.metrics;

import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.service.metrics.restock.RestockParams;
import com.usal.whbackend.telemetry.OtelTelemetryAdapter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for what may be queried.
 *
 * <p>Serving {@code GET /metrics/catalog} and validating {@code POST /metrics/query} from the same
 * list is what removes the injection surface: a metric, dimension or aggregation absent here simply
 * cannot reach VictoriaMetrics.
 *
 * <p>{@link #all()} holds raw fleet signals only. Nothing derived — no MTBF, no failure rate —
 * because those are ratios of these series and belong to whoever is drawing the chart. Adding one
 * is an entry here plus a recording call in {@code TelemetryPort}.
 *
 * <p>{@link #computed()} is the other kind: business metrics whose formula is the point, so the
 * backend owns it and every team gets the same number. Each is served by its own endpoint; adding
 * one is an entry here plus that endpoint.
 */
@Component
public class MetricRegistry {

  private static final Set<UserRole> READERS =
      Set.of(
          UserRole.SUPERADMIN, UserRole.ADMIN_SYSTEM, UserRole.ADMIN_WAREHOUSE, UserRole.DASHBOARD);

  private static final Set<UserRole> SUPERADMIN_ONLY = Set.of(UserRole.SUPERADMIN);

  private final List<MetricDescriptor> descriptors =
      List.of(
          new MetricDescriptor(
              OtelTelemetryAdapter.BATTERY_METRIC,
              "wh_vehicle_battery",
              "Vehicle battery",
              "%",
              MetricType.GAUGE,
              List.of("vehicle_id"),
              List.of(Aggregation.AVG, Aggregation.MIN, Aggregation.MAX, Aggregation.LAST),
              READERS),
          new MetricDescriptor(
              OtelTelemetryAdapter.STATE_METRIC,
              "wh_vehicle_state",
              "Vehicle state",
              "1",
              MetricType.GAUGE,
              List.of("vehicle_id", "state"),
              // COUNT answers "how many rovers were in this state at once"; AVG answers "what
              // fraction of the window one rover spent there" — the input to a mean time to
              // recovery, which the caller divides for itself.
              List.of(Aggregation.COUNT, Aggregation.AVG, Aggregation.MAX, Aggregation.LAST),
              READERS),
          new MetricDescriptor(
              OtelTelemetryAdapter.TRANSITIONS_METRIC,
              "wh_vehicle_transitions",
              "Vehicle status transitions",
              "1",
              MetricType.COUNTER,
              List.of("vehicle_id", "from", "to", "category"),
              List.of(Aggregation.INCREASE, Aggregation.RATE),
              READERS),
          histogram(
              "http.server.duration",
              "HTTP request duration",
              List.of("route", "method", "status_class")),
          histogram(
              "mongo.command.duration",
              "Mongo command duration",
              List.of("collection", "command", "outcome")),
          counter("kafka.messages", "Kafka messages", List.of("topic", "direction", "result")),
          histogram("kafka.publish.duration", "Kafka publish duration", List.of("topic", "result")),
          gauge("ws.sessions", "WebSocket sessions", "1", List.of("feed")),
          counter("ws.messages", "WebSocket messages", List.of("feed", "result")),
          gauge("jvm.heap.used", "JVM heap used", "By", List.of()),
          gauge("jvm.threads", "JVM threads", "1", List.of()),
          counter("jvm.gc.time", "JVM GC time", "ms", List.of("gc")),
          gauge("tomcat.threads", "Tomcat threads", "1", List.of("state")),
          gauge("tomcat.connections", "Tomcat connections", "1", List.of("state")),
          gauge("http.server.active", "HTTP requests in flight", "1", List.of()),
          gauge("mongo.pool.connections", "Mongo pool connections", "1", List.of("state")),
          histogram("mongo.pool.checkout", "Mongo pool checkout time", List.of()),
          counter("mongo.pool.checkout.failed", "Mongo pool checkouts failed", List.of("reason")),
          counter("bridge.messages", "Bridge messages", List.of("flow", "result")),
          histogram("bridge.callback.duration", "Bridge callback duration", List.of("flow")),
          histogram(
              "bridge.publish.duration", "Bridge publish duration", List.of("flow", "result")),
          histogram(
              "bridge.processing.duration",
              "Bridge processing duration",
              List.of("flow", "result")),
          gauge("bridge.consumer.lag", "Bridge consumer lag", "1", List.of("flow")),
          gauge("job.run", "Cron job run", "1", List.of("job", "result", "field")),
          // Scraped series (docs/CONSOLE.md, "Scraped series"): the targets name them, so each
          // seriesName is the verified stored name rather than derived from the dotted one, and
          // the dimensions are only the labels that series carries. job, instance, MinIO's server
          // and the collector's per-restart service_instance_id are incidental and not offered.
          gauge(
              "minio.bucket.size",
              "minio_cluster_usage_total_bytes",
              // Cluster-wide usage: per-bucket size is not scraped, and there is one bucket.
              "MinIO bucket size",
              "By",
              List.of()),
          gauge(
              "minio.objects", "minio_cluster_usage_object_total", "MinIO objects", "1", List.of()),
          counter(
              "minio.requests",
              "minio_s3_requests_total",
              "MinIO S3 requests",
              "1",
              List.of("api")),
          // MinIO counts 4xx and 5xx under two series names; the collector folds them into one
          // series with a class label (collector-config.yml), so summing over class is the total.
          counter(
              "minio.errors",
              "minio_s3_requests_errors_by_class_total",
              "MinIO S3 errors",
              "1",
              List.of("api", "class")),
          // Stored as cumulative le buckets under the bare name, with no _count to rate, so it
          // offers quantiles only; minio.requests counts the same requests.
          new MetricDescriptor(
              "minio.latency",
              "minio_s3_requests_ttfb_seconds_distribution",
              "MinIO S3 time to first byte",
              "s",
              MetricType.HISTOGRAM,
              List.of("api"),
              List.of(Aggregation.P50, Aggregation.P95, Aggregation.P99),
              SUPERADMIN_ONLY,
              "minio_s3_requests_ttfb_seconds_distribution"),
          counter(
              "minio.traffic.received",
              "minio_s3_traffic_received_bytes",
              "MinIO S3 traffic received",
              "By",
              List.of()),
          counter(
              "minio.traffic.sent",
              "minio_s3_traffic_sent_bytes",
              "MinIO S3 traffic sent",
              "By",
              List.of()),
          counter(
              "collector.received",
              "otelcol_receiver_accepted_metric_points",
              "Collector points received",
              "1",
              List.of("receiver", "transport")),
          counter(
              "collector.refused",
              "otelcol_receiver_refused_metric_points",
              "Collector points refused",
              "1",
              List.of("receiver", "transport")),
          counter(
              "collector.exported",
              "otelcol_exporter_sent_metric_points",
              "Collector points exported",
              "1",
              List.of("exporter")),
          counter(
              "collector.export.failed",
              "otelcol_exporter_send_failed_metric_points",
              "Collector points failed to export",
              "1",
              List.of("exporter")),
          histogram(
              "collector.export.duration",
              "otelcol_exporter_http_duration",
              "Collector export duration",
              "ms",
              List.of("net_peer_name", "http_status_code")),
          // Points an exporter could not queue (queue full): the collector's only drop. The series
          // only appears after the first one.
          counter(
              "collector.dropped",
              "otelcol_exporter_enqueue_failed_metric_points",
              "Collector points dropped",
              "1",
              List.of("exporter")),
          gauge(
              "collector.queue.size",
              "otelcol_exporter_queue_size",
              "Collector export queue size",
              "1",
              List.of("exporter", "data_type")),
          counter(
              "vm.rows.ingested",
              "vm_rows_inserted_total",
              "VictoriaMetrics rows ingested",
              "1",
              List.of("type")),
          // vm_rows_invalid_total (parse failures, by type) is intentionally not catalogued: the
          // panel charts rows VictoriaMetrics dropped, which vm_rows_ignored_total counts by
          // reason.
          counter(
              "vm.rows.rejected",
              "vm_rows_ignored_total",
              "VictoriaMetrics rows rejected",
              "1",
              List.of("reason")),
          // Filter type="storage/hour_metric_ids" for the active series count.
          gauge(
              "vm.series.active",
              "vm_cache_entries",
              "VictoriaMetrics cache entries",
              "1",
              List.of("type")),
          // The total spans the type series, so this gauge also offers COUNT, which here means
          // "sum across series" (avg_over_time per series, then sum): the disk used in all.
          new MetricDescriptor(
              "vm.disk.used",
              "vm_data_size_bytes",
              "VictoriaMetrics data size",
              "By",
              MetricType.GAUGE,
              List.of("type"),
              List.of(Aggregation.AVG, Aggregation.MAX, Aggregation.LAST, Aggregation.COUNT),
              SUPERADMIN_ONLY),
          gauge(
              "vm.disk.free",
              "vm_free_disk_space_bytes",
              "VictoriaMetrics free disk",
              "By",
              List.of("path")),
          // A summary: quantiles arrive precomputed, one gauge per quantile label value.
          gauge(
              "vm.query.duration",
              "vm_request_duration_seconds",
              "VictoriaMetrics request duration",
              "s",
              List.of("path", "quantile")),
          // VictoriaMetrics has no status label: requests (errors included) and errors are two
          // series, so the error ratio is vm.errors over vm.requests, by path.
          counter(
              "vm.requests",
              "vm_request_duration_seconds_count",
              "VictoriaMetrics requests",
              "1",
              List.of("path")),
          counter(
              "vm.errors",
              "vm_http_request_errors_total",
              "VictoriaMetrics request errors",
              "1",
              List.of("path")),
          // up is one series per scrape job; the selector pins Caddy's (1 scraped, 0 down).
          gauge("edge.up", "up{job=\"caddy\"}", "Edge scrape up", "1", List.of()),
          counter(
              "edge.requests",
              "caddy_http_requests_total",
              "Edge requests",
              "1",
              List.of("server", "handler", "host")),
          // Rate by code gives the status classes and the 401 and 403 rates.
          histogram(
              "edge.duration",
              "caddy_http_request_duration_seconds",
              "Edge request duration",
              "s",
              List.of("server", "handler", "host", "code", "method")),
          counter(
              "edge.errors",
              "caddy_http_request_errors_total",
              "Edge handler errors",
              "1",
              List.of("server", "handler", "host")));

  private final List<ComputedMetricDescriptor> computed =
      List.of(
          new ComputedMetricDescriptor(
              "restock_suggestions",
              "/metrics/restock-suggestions",
              "Sugerencia de reposición",
              "Demanda diaria ponderada y, por producto, si hay que reponer y cuánto.",
              List.of(
                  number(
                      "alpha", 0.0, 1.0, "Peso de la demanda reciente frente a la de largo plazo"),
                  integer(
                      "recent_days",
                      1,
                      RestockParams.MAX_WINDOW_DAYS - 1,
                      "Días hacia atrás que cuentan como período reciente"),
                  integer(
                      "long_days",
                      2,
                      RestockParams.MAX_WINDOW_DAYS,
                      "Días hacia atrás del período largo; debe ser mayor que recent_days"),
                  number(
                      "safety_days", 0.0, null, "Días de demanda que cubre el stock de seguridad"),
                  number("lead_time_days", 0.0, null, "Días que tarda en llegar una reposición"),
                  number(
                      "coverage_days",
                      0.0,
                      null,
                      "Días de stock que se busca cubrir después de reponer")),
              List.of(
                  new ComputedMetricDescriptor.Field(
                      "product_ids",
                      "string[]",
                      null,
                      null,
                      "Limita a estos productos; vacío o ausente = todos los activos"),
                  new ComputedMetricDescriptor.Field(
                      "category", "string", null, null, "Limita a una categoría del catálogo")),
              READERS),
          new ComputedMetricDescriptor(
              "suggestion_conversion",
              "/metrics/suggestion-conversion",
              "Conversión de sugerencias",
              "Por día, semana o mes, cuántos pedidos, líneas y unidades salieron de una"
                  + " recomendación (from_recommendation en POST /orders) y qué proporción"
                  + " representan. Los pedidos cancelados no cuentan.",
              List.of(
                  new ComputedMetricDescriptor.Field(
                      "from", "string", null, null, "Inicio del rango, inclusive (ISO-8601)"),
                  new ComputedMetricDescriptor.Field(
                      "to",
                      "string",
                      null,
                      null,
                      "Fin del rango, exclusivo (ISO-8601); como máximo 731 días después de from"),
                  new ComputedMetricDescriptor.Field(
                      "granularity",
                      "string",
                      null,
                      null,
                      "Tamaño del bucket: day, week (lunes a domingo) o month")),
              List.of(
                  new ComputedMetricDescriptor.Field(
                      "time_zone",
                      "string",
                      null,
                      null,
                      "Zona horaria IANA con la que se cortan los buckets; UTC si se omite"),
                  new ComputedMetricDescriptor.Field(
                      "product_ids",
                      "string[]",
                      null,
                      null,
                      "Considera solo las líneas de estos productos; vacío o ausente = todos"),
                  new ComputedMetricDescriptor.Field(
                      "category",
                      "string",
                      null,
                      null,
                      "Considera solo las líneas de una categoría del catálogo")),
              READERS));

  private static MetricDescriptor histogram(
      String name, String displayName, List<String> dimensions) {
    return histogram(name, series(name), displayName, "ms", dimensions);
  }

  private static MetricDescriptor histogram(
      String name, String seriesName, String displayName, String unit, List<String> dimensions) {
    return new MetricDescriptor(
        name,
        seriesName,
        displayName,
        unit,
        MetricType.HISTOGRAM,
        dimensions,
        List.of(
            Aggregation.P50,
            Aggregation.P95,
            Aggregation.P99,
            Aggregation.RATE,
            Aggregation.INCREASE),
        SUPERADMIN_ONLY);
  }

  private static MetricDescriptor counter(
      String name, String displayName, List<String> dimensions) {
    return counter(name, displayName, "1", dimensions);
  }

  private static MetricDescriptor counter(
      String name, String displayName, String unit, List<String> dimensions) {
    return counter(name, series(name), displayName, unit, dimensions);
  }

  private static MetricDescriptor counter(
      String name, String seriesName, String displayName, String unit, List<String> dimensions) {
    return new MetricDescriptor(
        name,
        seriesName,
        displayName,
        unit,
        MetricType.COUNTER,
        dimensions,
        List.of(Aggregation.RATE, Aggregation.INCREASE),
        SUPERADMIN_ONLY);
  }

  private static MetricDescriptor gauge(
      String name, String displayName, String unit, List<String> dimensions) {
    return gauge(name, series(name), displayName, unit, dimensions);
  }

  private static MetricDescriptor gauge(
      String name, String seriesName, String displayName, String unit, List<String> dimensions) {
    return new MetricDescriptor(
        name,
        seriesName,
        displayName,
        unit,
        MetricType.GAUGE,
        dimensions,
        List.of(Aggregation.AVG, Aggregation.MAX, Aggregation.LAST),
        SUPERADMIN_ONLY);
  }

  /** VictoriaMetrics stores the dotted name with underscores and no unit suffix. */
  private static String series(String name) {
    return name.replace('.', '_');
  }

  private static ComputedMetricDescriptor.Field number(
      String name, Double min, Double max, String description) {
    return new ComputedMetricDescriptor.Field(name, "number", min, max, description);
  }

  private static ComputedMetricDescriptor.Field integer(
      String name, int min, int max, String description) {
    return new ComputedMetricDescriptor.Field(
        name, "integer", (double) min, (double) max, description);
  }

  public List<MetricDescriptor> all() {
    return descriptors;
  }

  public Optional<MetricDescriptor> findByName(String name) {
    return descriptors.stream().filter(d -> d.name().equals(name)).findFirst();
  }

  /** The catalogue as a given caller may see it — metrics they cannot query are not listed. */
  public List<MetricDescriptor> visibleTo(Set<UserRole> roles) {
    return descriptors.stream().filter(d -> d.isVisibleTo(roles)).toList();
  }

  public List<ComputedMetricDescriptor> computed() {
    return computed;
  }

  /** Computed metrics a given caller may request. */
  public List<ComputedMetricDescriptor> computedVisibleTo(Set<UserRole> roles) {
    return computed.stream().filter(d -> d.isVisibleTo(roles)).toList();
  }
}
