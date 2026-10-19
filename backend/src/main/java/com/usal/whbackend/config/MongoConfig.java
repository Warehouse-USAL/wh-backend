package com.usal.whbackend.config;

import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.telemetry.MongoMetricsListener;
import com.usal.whbackend.telemetry.TelemetryPort;
import com.usal.whbackend.telemetry.TelemetryProperties;
import java.util.List;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

@Configuration
@EnableMongoAuditing
public class MongoConfig {

  @Bean
  MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
    return new MongoTransactionManager(dbFactory);
  }

  /**
   * The port is injected lazily: building the Mongo client must not wait on the telemetry graph,
   * which reads fleet state through repositories that need that same client.
   */
  @Bean
  MongoMetricsListener mongoMetricsListener(
      @Lazy TelemetryPort telemetry, TelemetryProperties props) {
    return new MongoMetricsListener(telemetry, props);
  }

  @Bean
  MongoClientSettingsBuilderCustomizer mongoMetricsCustomizer(MongoMetricsListener listener) {
    return builder ->
        builder
            .addCommandListener(listener)
            .applyToConnectionPoolSettings(pool -> pool.addConnectionPoolListener(listener));
  }

  /**
   * Tolerates legacy {@link StockSize} strings persisted by older versions (e.g. {@code MEDIANO},
   * {@code GRANDE}) when reading documents, mapping them onto the current enum instead of failing
   * with "No enum constant ... StockSize.MEDIANO". Writes keep Spring Data's default enum-name
   * behaviour, so values are migrated to the canonical name the next time a document is saved.
   */
  @Bean
  MongoCustomConversions mongoCustomConversions() {
    return new MongoCustomConversions(List.of(new StringToStockSizeConverter()));
  }

  @ReadingConverter
  static class StringToStockSizeConverter implements Converter<String, StockSize> {
    @Override
    public StockSize convert(String source) {
      return StockSize.fromValue(source);
    }
  }
}
