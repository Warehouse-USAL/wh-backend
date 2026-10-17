package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

class KafkaConfigTest {

  private static final String BOOTSTRAP = "localhost:9092";

  private final KafkaConfig config = new KafkaConfig();

  @Test
  void producerFactory_usesConfiguredBootstrapServers() {
    ProducerFactory<String, String> factory = config.producerFactory(BOOTSTRAP);

    assertThat(factory.getConfigurationProperties())
        .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP)
        .containsKey(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)
        .containsKey(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
  }

  @Test
  void kafkaTemplate_wrapsTheProducerFactory() {
    ProducerFactory<String, String> factory = config.producerFactory(BOOTSTRAP);
    KafkaTemplate<String, String> template = config.kafkaTemplate(factory);

    assertThat(template.getProducerFactory()).isSameAs(factory);
  }
}
