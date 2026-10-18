package com.usal.whbackend.service.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ConsolePropertiesTest {

  @Test
  void streamDefaults() {
    ConsoleProperties.Stream stream = new ConsoleProperties().getStream();

    assertThat(stream.getMetricInterval()).isEqualTo(Duration.ofSeconds(5));
    assertThat(stream.getLogsInterval()).isEqualTo(Duration.ofSeconds(2));
    assertThat(stream.getLogsOverlap()).isEqualTo(Duration.ofSeconds(10));
    assertThat(stream.getStatusInterval()).isEqualTo(Duration.ofSeconds(5));
    assertThat(stream.getHeartbeat()).isEqualTo(Duration.ofSeconds(15));
    assertThat(stream.getSendTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(stream.getMaxSubscriptions()).isEqualTo(200);
    assertThat(stream.getMaxStreamsPerPrincipal()).isEqualTo(5);
    assertThat(stream.getMaxStreams()).isEqualTo(50);
    assertThat(stream.getQueueCapacity()).isEqualTo(1000);
  }

  @Test
  void everyStreamKeyBinds() {
    ConsoleProperties properties = new ConsoleProperties();
    Map<String, String> source =
        Map.ofEntries(
            Map.entry("console.stream.metric-interval", "6s"),
            Map.entry("console.stream.logs-interval", "3s"),
            Map.entry("console.stream.logs-overlap", "20s"),
            Map.entry("console.stream.status-interval", "7s"),
            Map.entry("console.stream.heartbeat", "10s"),
            Map.entry("console.stream.send-timeout", "1m"),
            Map.entry("console.stream.max-subscriptions", "100"),
            Map.entry("console.stream.max-streams-per-principal", "2"),
            Map.entry("console.stream.max-streams", "20"),
            Map.entry("console.stream.queue-capacity", "500"));

    new Binder(new MapConfigurationPropertySource(source))
        .bind("console", Bindable.ofInstance(properties));

    ConsoleProperties.Stream stream = properties.getStream();
    assertThat(stream.getMetricInterval()).isEqualTo(Duration.ofSeconds(6));
    assertThat(stream.getLogsInterval()).isEqualTo(Duration.ofSeconds(3));
    assertThat(stream.getLogsOverlap()).isEqualTo(Duration.ofSeconds(20));
    assertThat(stream.getStatusInterval()).isEqualTo(Duration.ofSeconds(7));
    assertThat(stream.getHeartbeat()).isEqualTo(Duration.ofSeconds(10));
    assertThat(stream.getSendTimeout()).isEqualTo(Duration.ofMinutes(1));
    assertThat(stream.getMaxSubscriptions()).isEqualTo(100);
    assertThat(stream.getMaxStreamsPerPrincipal()).isEqualTo(2);
    assertThat(stream.getMaxStreams()).isEqualTo(20);
    assertThat(stream.getQueueCapacity()).isEqualTo(500);
  }
}
