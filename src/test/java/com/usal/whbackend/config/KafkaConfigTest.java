package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
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

  @Test
  void consumerFactory_usesConfiguredBootstrapServersAndGroup() {
    ConsumerFactory<String, String> factory = config.consumerFactory(BOOTSTRAP);

    assertThat(factory.getConfigurationProperties())
        .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP)
        .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "wh-backend")
        .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
  }

  @Test
  void listenerContainerFactory_wiresTheConsumerFactory() {
    ConsumerFactory<String, String> consumerFactory = config.consumerFactory(BOOTSTRAP);
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        config.kafkaListenerContainerFactory(consumerFactory);

    assertThat(factory.getConsumerFactory()).isSameAs(consumerFactory);
  }
}
