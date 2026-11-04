package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoDatabaseFactory;

class MongoConfigTest {

  @Test
  void transactionManager_isBoundToTheGivenFactory() {
    MongoDatabaseFactory factory = mock(MongoDatabaseFactory.class);

    var manager = new MongoConfig().transactionManager(factory);

    assertThat(manager).isNotNull();
    assertThat(manager.getDatabaseFactory()).isSameAs(factory);
  }

  @Test
  void customizerRegistersTheListenerForCommandsAndPool() {
    var listener =
        new MongoConfig()
            .mongoMetricsListener(
                new com.usal.whbackend.telemetry.NoOpTelemetryAdapter(),
                new com.usal.whbackend.telemetry.TelemetryProperties());
    var builder = com.mongodb.MongoClientSettings.builder();

    new MongoConfig().mongoMetricsCustomizer(listener).customize(builder);

    var settings = builder.build();
    assertThat(settings.getCommandListeners()).containsExactly(listener);
    assertThat(settings.getConnectionPoolSettings().getConnectionPoolListeners())
        .containsExactly(listener);
  }

  @Test
  void timeoutsCustomizerBoundsServerSelectionConnectAndRead() {
    var builder = com.mongodb.MongoClientSettings.builder();

    new MongoConfig().mongoTimeoutsCustomizer(2500, 1500, 20000).customize(builder);

    var settings = builder.build();
    var ms = java.util.concurrent.TimeUnit.MILLISECONDS;
    assertThat(settings.getClusterSettings().getServerSelectionTimeout(ms)).isEqualTo(2500);
    assertThat(settings.getSocketSettings().getConnectTimeout(ms)).isEqualTo(1500);
    assertThat(settings.getSocketSettings().getReadTimeout(ms)).isEqualTo(20000);
  }
}
