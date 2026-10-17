package com.usal.whbackend.config;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
@EnableKafka
public class KafkaConfig {

  @Bean
  public ProducerFactory<String, String> producerFactory(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    return new DefaultKafkaProducerFactory<>(
        Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
  }

  @Bean
  public KafkaTemplate<String, String> kafkaTemplate(
      ProducerFactory<String, String> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
  }

  @Bean
  public ConsumerFactory<String, String> consumerFactory(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    return new DefaultKafkaConsumerFactory<>(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG,
            "wh-backend",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class));
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    return factory;
  }
}
