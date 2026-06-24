package com.usal.whbackend.config;

import com.usal.whbackend.domain.StockSize;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
   * Tolerates legacy {@link StockSize} strings persisted by older versions (e.g.
   * {@code MEDIANO}, {@code GRANDE}) when reading documents, mapping them onto the
   * current enum instead of failing with "No enum constant ... StockSize.MEDIANO".
   * Writes keep Spring Data's default enum-name behaviour, so values are migrated
   * to the canonical name the next time a document is saved.
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
