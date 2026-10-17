package com.usal.whbackend.config;

import com.usal.whbackend.domain.StockSize;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Makes {@code @RequestParam StockSize} bindings (e.g. GET /warehouse/positions/available) accept
 * the same lenient vocabulary {@link StockSize#fromValue} already accepts for JSON request bodies
 * via {@code @JsonCreator} — Spring's default enum query-param binding is strict {@code
 * Enum.valueOf} and does not otherwise go through {@code fromValue}.
 */
@Component
public class StockSizeConverter implements Converter<String, StockSize> {
  @Override
  public StockSize convert(String source) {
    return StockSize.fromValue(source);
  }
}
