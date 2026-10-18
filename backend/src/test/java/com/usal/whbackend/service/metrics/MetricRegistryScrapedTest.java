package com.usal.whbackend.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usal.whbackend.api.metrics.MetricsQueryRequest;
import com.usal.whbackend.domain.UserRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * The series scraped from MinIO, the collector, VictoriaMetrics and Caddy. Their names are not ours
 * to choose, so the expected names are read from the verified tables in {@code docs/CONSOLE.md}
 * rather than repeated here.
 */
class MetricRegistryScrapedTest {

  private static final Pattern SERIES_TOKEN = Pattern.compile("`([a-z][a-z0-9_]*)[`{]");
  private static final Pattern ANY_TOKEN = Pattern.compile("`([a-z][a-z0-9_]*)");
  private static final Pattern SELECTOR_LABEL = Pattern.compile("([a-z][a-z0-9_]*)=");

  /** The logical names the Infrastructure and Monitoring pages chart. */
  private static final List<String> SCRAPED =
      List.of(
          "minio.bucket.size",
          "minio.objects",
          "minio.requests",
          "minio.errors",
          "minio.latency",
          "minio.traffic.received",
          "minio.traffic.sent",
          "collector.received",
          "collector.refused",
          "collector.exported",
          "collector.export.failed",
          "collector.export.duration",
          "collector.queue.size",
          "vm.rows.ingested",
          "vm.rows.rejected",
          "vm.series.active",
          "vm.disk.used",
          "vm.disk.free",
          "vm.query.duration",
          "edge.requests",
          "edge.duration",
          "edge.errors");

  private static final Instant FROM = Instant.parse("2026-10-18T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-10-18T01:00:00Z");

  /** Series name from the doc's "Series" column, mapped to the labels the doc gives it. */
  private static Map<String, Set<String>> documented;

  private final MetricRegistry registry = new MetricRegistry();
  private final MetricsQueryTranslator translator = new MetricsQueryTranslator();

  @BeforeAll
  static void readTheVerifiedNames() throws IOException {
    documented = parseScrapedSeries(Files.readString(consoleDoc()));
    assertThat(documented)
        .as("no series parsed from the 'Scraped series' tables of docs/CONSOLE.md")
        .isNotEmpty();
  }

  /** Walks up from the working directory (backend/ under Gradle) to the repo's docs/CONSOLE.md. */
  private static Path consoleDoc() {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve("docs").resolve("CONSOLE.md");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError(
        "docs/CONSOLE.md not found in "
            + Path.of("").toAbsolutePath()
            + " or any parent; it holds the verified scraped series names this test checks");
  }

  /**
   * Reads the table rows of the "Scraped series" section only. Series names are the backticked
   * tokens of the "Series" cell; a token directly followed by a selector ({@code name{...}}) counts
   * too. Each maps to its labels: the backticked tokens of the "Labels" cell, the label names in
   * its own selector braces, and the backticked tokens in a parenthesis right after it (the doc's
   * {@code `vm_rows_ignored_total` (`reason`)} form).
   */
  static Map<String, Set<String>> parseScrapedSeries(String markdown) {
    Map<String, Set<String>> series = new HashMap<>();
    boolean inSection = false;
    for (String line : markdown.split("\n", -1)) {
      if (line.startsWith("## ")) {
        inSection = line.trim().equals("## Scraped series");
        continue;
      }
      if (!inSection || !line.startsWith("|")) {
        continue;
      }
      String[] cells = line.split("\\|", -1);
      if (cells.length < 4) {
        continue;
      }
      String cell = cells[2];
      Set<String> labelsCell = tokens(ANY_TOKEN, cells[3]);
      Matcher m = SERIES_TOKEN.matcher(cell);
      while (m.find()) {
        Set<String> labels = new HashSet<>(labelsCell);
        int rest = m.end();
        if (cell.charAt(m.end() - 1) == '{') {
          int close = cell.indexOf('}', rest);
          labels.addAll(tokens(SELECTOR_LABEL, cell.substring(rest, close)));
          rest = close + 1;
        }
        String after = cell.substring(rest).replaceFirst("^`", "").stripLeading();
        if (after.startsWith("(") && after.indexOf(')') > 0) {
          labels.addAll(tokens(ANY_TOKEN, after.substring(0, after.indexOf(')'))));
        }
        series.computeIfAbsent(m.group(1), k -> new HashSet<>()).addAll(labels);
      }
    }
    return series;
  }

  private static Set<String> tokens(Pattern pattern, String text) {
    Set<String> found = new HashSet<>();
    Matcher m = pattern.matcher(text);
    while (m.find()) {
      found.add(m.group(1));
    }
    return found;
  }

  /** The stored series a descriptor's queries read: buckets and count for a histogram. */
  private static List<String> readSeries(MetricDescriptor d) {
    if (d.type() != MetricType.HISTOGRAM) {
      return List.of(d.seriesName());
    }
    return d.permits(Aggregation.RATE)
        ? List.of(d.bucketSeriesName(), d.seriesName() + "_count")
        : List.of(d.bucketSeriesName());
  }

  private String query(String metric, Map<String, String> filters, List<String> by, String agg) {
    return translator.translate(
        new MetricsQueryRequest(metric, FROM, TO, "30s", filters, by, agg),
        registry.findByName(metric).orElseThrow());
  }

  @Test
  void everyScrapedMetricTheConsoleChartsIsInTheCatalogue() {
    assertThat(SCRAPED).allSatisfy(name -> assertThat(registry.findByName(name)).isPresent());
  }

  @Test
  void scrapedDescriptorsPointAtTheVerifiedSeriesNames() {
    for (String name : SCRAPED) {
      MetricDescriptor d = registry.findByName(name).orElseThrow();
      Set<String> labels = new HashSet<>();
      for (String series : readSeries(d)) {
        // The collector's export latency lists "_bucket / _sum / _count" on one row, so its count
        // series appears only as a suffix of the bucket series it shares a row with.
        String listed =
            documented.containsKey(series) || !series.endsWith("_count")
                ? series
                : series.replaceFirst("_count$", "_bucket");
        assertThat(documented)
            .as("%s reads %s, which docs/CONSOLE.md does not list", name, series)
            .containsKey(listed);
        labels.addAll(documented.get(listed));
      }
      assertThat(labels)
          .as("%s may only offer labels its series carries", name)
          .containsAll(d.dimensions());
    }
  }

  @Test
  void scrapedMetricsAreSuperadminOnly() {
    assertThat(SCRAPED)
        .allSatisfy(
            name ->
                assertThat(registry.findByName(name).orElseThrow().requiredRoles())
                    .isEqualTo(Set.of(UserRole.SUPERADMIN)));
    for (UserRole role : UserRole.values()) {
      if (role != UserRole.SUPERADMIN) {
        assertThat(registry.visibleTo(Set.of(role)))
            .extracting(MetricDescriptor::name)
            .doesNotContainAnyElementsOf(SCRAPED);
      }
    }
  }

  @Test
  void scrapedSeriesCarryTheTypeAndUnitTheDocGives() {
    Map<String, MetricType> types =
        Map.of(
            "minio.requests", MetricType.COUNTER,
            "minio.bucket.size", MetricType.GAUGE,
            "minio.latency", MetricType.HISTOGRAM,
            "collector.queue.size", MetricType.GAUGE,
            "collector.export.duration", MetricType.HISTOGRAM,
            "vm.rows.ingested", MetricType.COUNTER,
            // A summary: its quantiles are precomputed gauges keyed by the quantile label.
            "vm.query.duration", MetricType.GAUGE,
            "edge.duration", MetricType.HISTOGRAM);
    types.forEach(
        (name, type) -> assertThat(registry.findByName(name).orElseThrow().type()).isEqualTo(type));
    assertThat(List.of("minio.latency", "vm.query.duration", "edge.duration"))
        .allSatisfy(
            name -> assertThat(registry.findByName(name).orElseThrow().unit()).isEqualTo("s"));
    assertThat(registry.findByName("collector.export.duration").orElseThrow().unit())
        .isEqualTo("ms");
  }

  @Test
  void everyDescriptorOffersOnlyAggregationsValidForItsType() {
    assertThat(registry.all())
        .allSatisfy(
            d ->
                assertThat(d.permittedAggregations())
                    .as(d.name())
                    .isNotEmpty()
                    .allSatisfy(
                        a -> {
                          switch (d.type()) {
                            case COUNTER -> assertThat(a.isCounterDelta()).isTrue();
                            case GAUGE -> {
                              assertThat(a.isCounterDelta()).isFalse();
                              assertThat(a.quantile()).isEmpty();
                            }
                            case HISTOGRAM ->
                                assertThat(a.isCounterDelta() || a.quantile().isPresent()).isTrue();
                          }
                        }));
  }

  @Test
  void minioLatencyRatesItsUnsuffixedCumulativeBuckets() {
    assertThat(query("minio.latency", Map.of("api", "PutObject"), List.of(), "p95"))
        .isEqualTo(
            "histogram_quantile(0.95, sum by (le) "
                + "(rate(minio_s3_requests_ttfb_seconds_distribution{api=\"PutObject\"}[30s])))");
    assertThat(query("minio.latency", Map.of(), List.of("api"), "p50"))
        .isEqualTo(
            "histogram_quantile(0.5, sum by (le, api) "
                + "(rate(minio_s3_requests_ttfb_seconds_distribution[30s])))");
  }

  @Test
  void minioLatencyOffersNoRateBecauseItHasNoCountSeries() {
    assertThatThrownBy(() -> query("minio.latency", Map.of(), List.of(), "rate"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            t ->
                assertThat(((ResponseStatusException) t).getReason())
                    .isEqualTo("UNSUPPORTED_AGGREGATION"));
  }

  @Test
  void edgeDurationReadsCaddysSecondsHistogram() {
    assertThat(query("edge.duration", Map.of("host", "example.org"), List.of(), "p99"))
        .isEqualTo(
            "histogram_quantile(0.99, sum by (le) "
                + "(rate(caddy_http_request_duration_seconds_bucket{host=\"example.org\"}[30s])))");
    assertThat(query("edge.duration", Map.of(), List.of("code"), "rate"))
        .isEqualTo("sum by (code) (rate(caddy_http_request_duration_seconds_count[30s]))");
  }

  @Test
  void collectorExportDurationReadsTheRenamedClientHistogram() {
    assertThat(query("collector.export.duration", Map.of(), List.of("net_peer_name"), "p95"))
        .isEqualTo(
            "histogram_quantile(0.95, sum by (le, net_peer_name) "
                + "(rate(otelcol_exporter_http_duration_bucket[30s])))");
    assertThat(
            query(
                "collector.export.duration",
                Map.of("net_peer_name", "victoriametrics"),
                List.of("http_status_code"),
                "increase"))
        .isEqualTo(
            "sum by (http_status_code) (increase(otelcol_exporter_http_duration_count"
                + "{net_peer_name=\"victoriametrics\"}[30s]))");
  }

  @Test
  void conventionalHistogramsKeepTheBucketSuffix() {
    assertThat(registry.findByName("http.server.duration").orElseThrow().bucketSeriesName())
        .isEqualTo("http_server_duration_bucket");
  }

  @Test
  void theParserReadsOnlyTheScrapedSeriesTables() {
    String md =
        "## Other\n| A | `not_this` | `x` |\n## Scraped series\n"
            + "| Panel | Series | Labels |\n|---|---|---|\n"
            + "| P | `a_total` (counter), `b{type=\"x\"}` | `api` |\n"
            + "| Q | `c_total` (`reason`), `d_total` (`kind`) | |\n"
            + "short | row\n## Next\n| A | `nor_this` | |\n";
    Map<String, Set<String>> parsed = parseScrapedSeries(md);
    assertThat(parsed).containsOnlyKeys("a_total", "b", "c_total", "d_total", "reason", "kind");
    assertThat(parsed.get("a_total")).containsExactly("api");
    assertThat(parsed.get("b")).containsExactlyInAnyOrder("api", "type");
    assertThat(parsed.get("c_total")).containsExactly("reason");
    assertThat(parsed.get("d_total")).containsExactly("kind");
  }

  @Test
  void vmDiskUsedTotalsItsTypesWithCount() {
    assertThat(registry.findByName("vm.disk.used").orElseThrow().permittedAggregations())
        .containsExactly(Aggregation.AVG, Aggregation.MAX, Aggregation.LAST, Aggregation.COUNT);
    assertThat(query("vm.disk.used", Map.of(), List.of(), "count"))
        .isEqualTo("sum(avg_over_time(vm_data_size_bytes[30s]))");
  }

  @Test
  void noOtherScrapedGaugeOffersCount() {
    assertThat(SCRAPED)
        .filteredOn(name -> !name.equals("vm.disk.used"))
        .allSatisfy(
            name ->
                assertThat(registry.findByName(name).orElseThrow().permits(Aggregation.COUNT))
                    .as(name)
                    .isFalse());
  }

  @Test
  void minioBucketSizeIsNamedForThePanel() {
    assertThat(registry.findByName("minio.bucket.size").orElseThrow().displayName())
        .isEqualTo("MinIO bucket size");
  }
}
