package com.usal.whbackend.telemetry;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ships log records over OTLP through the {@code OpenTelemetryAppender} declared in {@code
 * logback-spring.xml}. The appender does nothing until it is installed here, which only happens
 * with {@code telemetry.enabled=true}; otherwise the provider has no processor and no exporter is
 * built, so logging behaves exactly as without this class.
 */
@Configuration
public class OtelLogsConfig {

  private static final Logger log = LoggerFactory.getLogger(OtelLogsConfig.class);

  @Bean(destroyMethod = "close")
  public SdkLoggerProvider sdkLoggerProvider(TelemetryProperties props) {
    return loggerProvider(
        props,
        () ->
            OtlpHttpLogRecordExporter.builder()
                .setEndpoint(props.getLogsEndpoint())
                .setTimeout(Duration.ofMillis(props.getExportTimeoutMs()))
                .build());
  }

  /** The exporter is supplied lazily so a disabled run never builds one. */
  SdkLoggerProvider loggerProvider(
      TelemetryProperties props, Supplier<LogRecordExporter> exporter) {
    SdkLoggerProviderBuilder builder =
        SdkLoggerProvider.builder().setResource(TelemetryConfig.resource(props));
    if (!props.isEnabled()) {
      return builder.build();
    }
    SdkLoggerProvider provider =
        builder
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter.get()).build())
            .build();
    OpenTelemetryAppender.install(OpenTelemetrySdk.builder().setLoggerProvider(provider).build());
    log.info("Log export enabled — shipping OTLP logs to {}", props.getLogsEndpoint());
    return provider;
  }
}
