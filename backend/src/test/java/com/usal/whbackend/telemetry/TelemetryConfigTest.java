package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.core.Ordered;

class TelemetryConfigTest {

  private final TelemetryConfig config = new TelemetryConfig();

  /** An empty fleet is enough: these tests assert which adapter is chosen, not what it observes. */
  private static final FleetStateSource EMPTY_FLEET =
      new FleetStateSource() {
        @Override
        public List<VehicleState> currentFleet() {
          return List.of();
        }

        @Override
        public List<String> knownStates() {
          return List.of();
        }
      };

  /** Hands the config a real listener, as the Mongo wiring does at runtime. */
  private static ObjectProvider<MongoMetricsListener> listenerProvider() {
    StaticListableBeanFactory factory = new StaticListableBeanFactory();
    factory.addBean(
        "listener",
        new MongoMetricsListener(new NoOpTelemetryAdapter(), new TelemetryProperties()));
    return factory.getBeanProvider(MongoMetricsListener.class);
  }

  @Test
  void usesNoOpAdapterWhenTelemetryIsDisabled() {
    TelemetryProperties props = new TelemetryProperties();
    props.setEnabled(false);

    OpenTelemetrySdk sdk = config.openTelemetrySdk(props);

    assertThat(config.telemetryPort(sdk, props, EMPTY_FLEET, listenerProvider()))
        .isInstanceOf(NoOpTelemetryAdapter.class);
    sdk.close();
  }

  @Test
  void usesOtelAdapterWhenTelemetryIsEnabled() {
    TelemetryProperties props = new TelemetryProperties();
    props.setEnabled(true);
    props.setEndpoint("http://localhost:4318/v1/metrics");

    OpenTelemetrySdk sdk = config.openTelemetrySdk(props);

    assertThat(config.telemetryPort(sdk, props, EMPTY_FLEET, listenerProvider()))
        .isInstanceOf(OtelTelemetryAdapter.class);
    sdk.close();
  }

  /**
   * Boots the real configuration and asks Spring Boot how it would register the filter: it must be
   * first in the servlet chain, ahead of security, or 401s and 403s go uncounted.
   */
  @Test
  void httpMetricsFilterIsRegisteredFirstAndRuntimeGaugesAreWired() {
    new ApplicationContextRunner()
        .withUserConfiguration(TelemetryConfig.class)
        .withBean(FleetStateSource.class, () -> EMPTY_FLEET)
        .run(
            context -> {
              assertThat(context).hasSingleBean(HttpMetricsFilter.class);
              assertThat(context).hasSingleBean(RuntimeGauges.class);
              List<Integer> orders =
                  new ServletContextInitializerBeans(context.getBeanFactory())
                      .stream()
                          .filter(FilterRegistrationBean.class::isInstance)
                          .map(FilterRegistrationBean.class::cast)
                          .filter(r -> r.getFilter() instanceof HttpMetricsFilter)
                          .map(FilterRegistrationBean::getOrder)
                          .toList();
              assertThat(orders).containsExactly(Ordered.HIGHEST_PRECEDENCE);
            });
  }

  @Test
  void telemetryIsDisabledByDefault() {
    assertThat(new TelemetryProperties().isEnabled()).isFalse();
  }
}
