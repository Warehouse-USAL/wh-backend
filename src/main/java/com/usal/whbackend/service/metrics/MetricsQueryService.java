package com.usal.whbackend.service.metrics;

import com.usal.whbackend.api.metrics.MetricsQueryRequest;
import com.usal.whbackend.api.metrics.MetricsQueryResponse;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.VictoriaMetricsRepository;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Orchestrates catalogue lookup, validation, translation and the VictoriaMetrics call. */
@Service
public class MetricsQueryService {

  static final int MAX_SERIES = 100;

  private final MetricRegistry registry;
  private final MetricsQueryTranslator translator;
  private final VictoriaMetricsRepository victoriaMetrics;

  public MetricsQueryService(
      MetricRegistry registry,
      MetricsQueryTranslator translator,
      VictoriaMetricsRepository victoriaMetrics) {
    this.registry = registry;
    this.translator = translator;
    this.victoriaMetrics = victoriaMetrics;
  }

  public MetricsQueryResponse query(MetricsQueryRequest request, Set<UserRole> roles) {
    MetricDescriptor descriptor =
        registry
            .findByName(request.metric())
            .filter(d -> d.isVisibleTo(roles))
            // A metric the caller may not read is reported as unknown rather than forbidden, so
            // the catalogue cannot be probed for metrics that exist but are out of reach.
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_METRIC"));

    String metricsQl = translator.translate(request, descriptor);

    List<VictoriaMetricsRepository.TimeSeries> series =
        victoriaMetrics.queryRange(metricsQl, request.from(), request.to(), request.step().trim());

    // Cardinality can only be known after the fact; refusing here keeps a runaway result from
    // being serialized to a dashboard that cannot draw it anyway.
    if (series.size() > MAX_SERIES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUERY_TOO_BROAD");
    }

    return new MetricsQueryResponse(
        descriptor.name(),
        descriptor.unit(),
        request.step().trim(),
        series.stream().map(s -> new MetricsQueryResponse.Series(s.labels(), s.points())).toList());
  }

  public List<MetricDescriptor> catalog(Set<UserRole> roles) {
    return registry.visibleTo(roles);
  }
}
