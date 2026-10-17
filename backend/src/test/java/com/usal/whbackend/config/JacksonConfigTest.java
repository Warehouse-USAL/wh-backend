package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.ModelConverter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JacksonConfigTest {

  private final JacksonConfig config = new JacksonConfig();

  record Sample(String productId, int currentStock) {}

  @Test
  void jsonCustomizer_appliesSnakeCaseNaming() {
    JsonMapper.Builder builder = JsonMapper.builder();
    config.jsonCustomizer().customize(builder);

    String json = builder.build().writeValueAsString(new Sample("p1", 3));

    assertThat(json).contains("product_id").contains("current_stock");
  }

  @Test
  void jsonCustomizer_acceptsCaseInsensitiveEnums() {
    JsonMapper.Builder builder = JsonMapper.builder();
    config.jsonCustomizer().customize(builder);

    var mapper = builder.build();
    var parsed = mapper.readValue("{\"size\":\"pallet\"}", EnumHolder.class);

    assertThat(parsed.size()).isEqualTo(com.usal.whbackend.domain.StockSize.PALLET);
  }

  record EnumHolder(com.usal.whbackend.domain.StockSize size) {}

  @Test
  void modelConverter_isProvidedForSpringdoc() {
    ModelConverter converter = config.modelConverter();
    assertThat(converter).isNotNull();
  }
}
