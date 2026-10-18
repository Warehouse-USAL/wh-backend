package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Loads the real logback-spring.xml into a private context. */
class LogbackConfigTest {

  private final LoggerContext context = new LoggerContext();
  private final ListAppender<ILoggingEvent> rootSeen = new ListAppender<>();

  @BeforeEach
  void load() throws Exception {
    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    configurator.doConfigure(getClass().getClassLoader().getResource("logback-spring.xml"));
    rootSeen.setContext(context);
    rootSeen.start();
    // Stands in for the OTEL appender: anything reaching the root logger would reach it too.
    context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(rootSeen);
  }

  @AfterEach
  void unload() {
    context.stop();
  }

  @Test
  void openTelemetryWarningsReachTheConsoleButNeverTheRootAppenders() {
    Logger sdk = context.getLogger("io.opentelemetry.x");

    sdk.warn("export failed");
    context.getLogger("com.usal.whbackend.x").warn("app warning");

    assertThat(context.getLogger("io.opentelemetry").getAppender("CONSOLE")).isNotNull();
    assertThat(context.getLogger("io.opentelemetry").isAdditive()).isFalse();
    assertThat(sdk.getEffectiveLevel()).isEqualTo(Level.WARN);
    assertThat(rootSeen.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly("app warning");
  }
}
