package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.repository.VictoriaMetricsRepository;
import com.usal.whbackend.repository.VictoriaMetricsRepository.SeriesData;
import com.usal.whbackend.service.metrics.MetricRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DemoTelemetrySeederTest {

  @Mock VictoriaMetricsRepository victoriaMetrics;

  private DemoTelemetrySeeder seeder() {
    return new DemoTelemetrySeeder(victoriaMetrics, new MetricRegistry());
  }

  private static List<Vehicle> fleet() {
    return List.of(vehicle("v-1"), vehicle("v-2"));
  }

  private static Vehicle vehicle(String id) {
    Vehicle v = new Vehicle();
    v.setId(id);
    return v;
  }

  @Test
  void writesHistoryOnAFreshStore() {
    when(victoriaMetrics.hasAnySeries(anyString())).thenReturn(false);
    when(victoriaMetrics.importSeries(any(), anyInt())).thenReturn(true);

    seeder().seed(fleet());

    ArgumentCaptor<List<SeriesData>> captor = ArgumentCaptor.captor();
    verify(victoriaMetrics).importSeries(captor.capture(), anyInt());
    assertThat(captor.getValue()).isNotEmpty();
    // Series names come from the registry, so seeded points land where live ones do.
    assertThat(captor.getValue())
        .allSatisfy(s -> assertThat(s.labels().get("__name__")).startsWith("wh_vehicle_"));
  }

  @Test
  void doesNotDoubleWriteWhenHistoryAlreadyExists() {
    when(victoriaMetrics.hasAnySeries(anyString())).thenReturn(true);

    seeder().seed(fleet());

    verify(victoriaMetrics, never()).importSeries(any(), anyInt());
  }

  @Test
  void neverThrows() {
    // Its whole contract. A malformed URI in the existence check once took application startup
    // down with it, because the catch below was narrower than the things that can go wrong.
    when(victoriaMetrics.hasAnySeries(anyString()))
        .thenThrow(new IllegalArgumentException("Invalid character '[' for QUERY_PARAM"));

    assertThatCode(() -> seeder().seed(fleet())).doesNotThrowAnyException();
  }

  @Test
  void survivesAnUnreachableMetricsStore() {
    when(victoriaMetrics.hasAnySeries(anyString())).thenReturn(false);
    when(victoriaMetrics.importSeries(any(), anyInt())).thenReturn(false);

    assertThatCode(() -> seeder().seed(fleet())).doesNotThrowAnyException();
  }

  @Test
  void doesNothingWithoutAFleet() {
    seeder().seed(List.of());

    verify(victoriaMetrics, never()).importSeries(any(), anyInt());
  }
}
