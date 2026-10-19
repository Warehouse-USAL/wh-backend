package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class OtelLogsConfigTest {

  private static final String MESSAGE = "otel-logs-config-test";

  private final OtelLogsConfig config = new OtelLogsConfig();
  private final CapturingExporter exporter = new CapturingExporter();
  private final AtomicBoolean exporterBuilt = new AtomicBoolean();
  private final Supplier<LogRecordExporter> exporterFactory =
      () -> {
        exporterBuilt.set(true);
        return exporter;
      };

  private Logger logger;
  private OpenTelemetryAppender appender;

  /** The same appender logback-spring.xml declares, attached to a private logger. */
  @BeforeEach
  void attachAppender() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    appender = new OpenTelemetryAppender();
    appender.setContext(context);
    appender.setCaptureMdcAttributes("request_id");
    appender.start();
    logger = context.getLogger(OtelLogsConfigTest.class);
    logger.setAdditive(false);
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    appender.stop();
    logger.setAdditive(true);
  }

  private static TelemetryProperties props(boolean enabled) {
    TelemetryProperties props = new TelemetryProperties();
    props.setEnabled(enabled);
    props.setServiceName("wh-backend-test");
    return props;
  }

  private List<LogRecordData> shipped() {
    return exporter.records.stream()
        .filter(r -> MESSAGE.equals(r.getBodyValue().asString()))
        .toList();
  }

  @Test
  void shipsLogsWithRequestIdAndServiceNameWhenEnabled() {
    SdkLoggerProvider provider = config.loggerProvider(props(true), exporterFactory);
    try {
      MDC.put("request_id", "abc-123");
      try {
        logger.info(MESSAGE);
      } finally {
        MDC.remove("request_id");
      }
      provider.forceFlush().join(5, TimeUnit.SECONDS);

      assertThat(shipped()).hasSize(1);
      LogRecordData record = shipped().get(0);
      assertThat(record.getAttributes().get(AttributeKey.stringKey("request_id")))
          .isEqualTo("abc-123");
      assertThat(record.getResource().getAttribute(AttributeKey.stringKey("service.name")))
          .isEqualTo("wh-backend-test");
    } finally {
      provider.close();
    }
  }

  @Test
  void logsConfigIsInertWhenTelemetryDisabled() {
    SdkLoggerProvider provider = config.loggerProvider(props(false), exporterFactory);
    try {
      logger.info(MESSAGE);
      provider.forceFlush().join(5, TimeUnit.SECONDS);

      assertThat(exporterBuilt).isFalse();
      assertThat(shipped()).isEmpty();
    } finally {
      provider.close();
    }
  }

  /** A disabled run drops what the appender buffered before install instead of holding it. */
  @Test
  void aDisabledRunReleasesTheStartupBuffer() {
    logger.info(MESSAGE);
    config.loggerProvider(props(false), exporterFactory).close();

    // Had the event stayed buffered, installing a live SDK now would replay it.
    SdkLoggerProvider provider = config.loggerProvider(props(true), exporterFactory);
    try {
      provider.forceFlush().join(5, TimeUnit.SECONDS);

      assertThat(shipped()).isEmpty();
    } finally {
      provider.close();
    }
  }

  @Test
  void beanBuildsTheOtlpExporterFromTheLogsEndpoint() {
    TelemetryProperties props = props(true);
    props.setLogsEndpoint("http://localhost:1/v1/logs");
    props.setExportTimeoutMs(100);

    SdkLoggerProvider provider = config.sdkLoggerProvider(props);

    assertThat(provider.toString()).contains("http://localhost:1/v1/logs");
    provider.close();
  }

  @Test
  void logsEndpointDefaultsToTheCollector() {
    assertThat(new TelemetryProperties().getLogsEndpoint())
        .isEqualTo("http://otel-collector:4318/v1/logs");
  }

  private static final class CapturingExporter implements LogRecordExporter {

    private final List<LogRecordData> records = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
      records.addAll(logs);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
