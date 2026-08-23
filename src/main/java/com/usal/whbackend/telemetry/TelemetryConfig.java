package com.usal.whbackend.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the telemetry pipeline.
 *
 * <p>Bean selection is imperative rather than {@code @ConditionalOnProperty}, so which adapter is
 * active is decided in one readable place and is trivial to assert in a test.
 */
@Configuration
@EnableConfigurationProperties(TelemetryProperties.class)
public class TelemetryConfig {

  private static final Logger log = LoggerFactory.getLogger(TelemetryConfig.class);

  static final String INSTRUMENTATION_SCOPE = "com.usal.whbackend";

  /**
   * When telemetry is disabled the SDK is built with no metric reader, so it is inert rather than
   * absent — that keeps the bean graph identical in both modes.
   */
  @Bean(destroyMethod = "close")
  public OpenTelemetrySdk openTelemetrySdk(TelemetryProperties props) {
    Resource resource =
        Resource.getDefault()
            .merge(
                Resource.create(
                    Attributes.of(AttributeKey.stringKey("service.name"), props.getServiceName())));

    SdkMeterProviderBuilder meterProvider = SdkMeterProvider.builder().setResource(resource);

    if (props.isEnabled()) {
      meterProvider.registerMetricReader(
          PeriodicMetricReader.builder(
                  OtlpHttpMetricExporter.builder()
                      .setEndpoint(props.getEndpoint())
                      .setTimeout(Duration.ofMillis(props.getExportTimeoutMs()))
                      .build())
              .setInterval(Duration.ofMillis(props.getExportIntervalMs()))
              .build());
      log.info(
          "Telemetry enabled — exporting OTLP to {} every {}ms",
          props.getEndpoint(),
          props.getExportIntervalMs());
    }

    return OpenTelemetrySdk.builder().setMeterProvider(meterProvider.build()).build();
  }

  @Bean
  public TelemetryPort telemetryPort(
      OpenTelemetrySdk sdk, TelemetryProperties props, FleetStateSource fleetStateSource) {
    if (!props.isEnabled()) {
      log.info("Telemetry disabled (telemetry.enabled=false) — samples are discarded");
      return new NoOpTelemetryAdapter();
    }
    return new OtelTelemetryAdapter(sdk.getMeter(INSTRUMENTATION_SCOPE), fleetStateSource);
  }
}
