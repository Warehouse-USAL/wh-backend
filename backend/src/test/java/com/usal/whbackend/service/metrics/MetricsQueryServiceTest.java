package com.usal.whbackend.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.api.metrics.MetricsQueryRequest;
import com.usal.whbackend.api.metrics.MetricsQueryResponse;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.VictoriaMetricsRepository;
import com.usal.whbackend.repository.VictoriaMetricsRepository.TimeSeries;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MetricsQueryServiceTest {

  @Mock VictoriaMetricsRepository victoriaMetrics;

  private final MetricRegistry registry = new MetricRegistry();
  private final MetricsQueryTranslator translator = new MetricsQueryTranslator();

  private MetricsQueryService service() {
    return new MetricsQueryService(registry, translator, victoriaMetrics);
  }

  private static MetricsQueryRequest request(String metric) {
    return new MetricsQueryRequest(
        metric,
        Instant.parse("2026-08-20T00:00:00Z"),
        Instant.parse("2026-08-21T00:00:00Z"),
        "5m",
        Map.of(),
        List.of("vehicle_id"),
        "avg");
  }

  private static String codeOf(Throwable t) {
    return ((ResponseStatusException) t).getReason();
  }

  @Test
  void returnsChartReadySeries() {
    when(victoriaMetrics.queryRange(anyString(), any(), any(), anyString()))
        .thenReturn(
            List.of(
                new TimeSeries(
                    Map.of("vehicle_id", "VHC-001"), List.of(List.of(1755648000L, 79.0)))));

    MetricsQueryResponse response =
        service().query(request("wh.vehicle.battery"), Set.of(UserRole.DASHBOARD));

    assertThat(response.metric()).isEqualTo("wh.vehicle.battery");
    assertThat(response.unit()).isEqualTo("%");
    assertThat(response.step()).isEqualTo("5m");
    assertThat(response.series()).hasSize(1);
    assertThat(response.series().get(0).labels()).containsEntry("vehicle_id", "VHC-001");
    assertThat(response.series().get(0).points()).containsExactly(List.of(1755648000L, 79.0));
  }

  @Test
  void rejectsUnknownMetricWithoutTouchingTheStore() {
    assertThatThrownBy(() -> service().query(request("wh.nope"), Set.of(UserRole.DASHBOARD)))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_METRIC"));

    verify(victoriaMetrics, never()).queryRange(anyString(), any(), any(), anyString());
  }

  @Test
  void reportsAMetricTheCallerCannotReadAsUnknownRatherThanForbidden() {
    // OPERATOR is not in the metric's requiredRoles. Saying "forbidden" would confirm the metric
    // exists and let the catalogue be probed.
    assertThatThrownBy(
            () -> service().query(request("wh.vehicle.battery"), Set.of(UserRole.OPERATOR)))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_METRIC"));

    verify(victoriaMetrics, never()).queryRange(anyString(), any(), any(), anyString());
  }

  @Test
  void rejectsAResultWithTooManySeries() {
    List<TimeSeries> flood =
        IntStream.range(0, MetricsQueryService.MAX_SERIES + 1)
            .mapToObj(i -> new TimeSeries(Map.of("vehicle_id", "V" + i), List.of()))
            .toList();
    when(victoriaMetrics.queryRange(anyString(), any(), any(), anyString())).thenReturn(flood);

    assertThatThrownBy(
            () -> service().query(request("wh.vehicle.battery"), Set.of(UserRole.DASHBOARD)))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
  }

  @Test
  void catalogHidesMetricsTheCallerCannotRead() {
    // Asserted by name rather than by count, so adding a metric does not fail a test about roles.
    assertThat(service().catalog(Set.of(UserRole.DASHBOARD)))
        .extracting(MetricDescriptor::name)
        .containsExactlyInAnyOrder(
            "wh.vehicle.battery", "wh.vehicle.state", "wh.vehicle.transitions");
    assertThat(service().catalog(Set.of(UserRole.OPERATOR))).isEmpty();
    assertThat(service().catalog(Set.of())).isEmpty();
  }

  @Test
  void passesTheTranslatedQueryToTheStore() {
    when(victoriaMetrics.queryRange(anyString(), any(), any(), anyString())).thenReturn(List.of());

    service().query(request("wh.vehicle.battery"), Set.of(UserRole.DASHBOARD));

    verify(victoriaMetrics)
        .queryRange(
            "avg by (vehicle_id)(avg_over_time(wh_vehicle_battery[5m]))",
            Instant.parse("2026-08-20T00:00:00Z"),
            Instant.parse("2026-08-21T00:00:00Z"),
            "5m");
  }
}
