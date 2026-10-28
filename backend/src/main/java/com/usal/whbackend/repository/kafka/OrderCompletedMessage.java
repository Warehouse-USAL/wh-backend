package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Payload of the {@code order.completed} topic. Field names are pinned to camelCase (unlike the
 * snake_case vehicle-facing topics) because the consumer, {@code wh-suggestions}, defines the
 * contract.
 */
public record OrderCompletedMessage(
    @JsonProperty("orderId") String orderId,
    @JsonProperty("userId") String userId,
    @JsonProperty("destinationArea") String destinationArea,
    @JsonProperty("items") List<Item> items,
    @JsonProperty("completedAt") String completedAt) {

  public OrderCompletedMessage {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(
      @JsonProperty("sku") String sku,
      @JsonProperty("productId") String productId,
      @JsonProperty("quantity") int quantity,
      // Omitted when false, so the payload of an order without suggestions is byte-for-byte what
      // it was before the field existed.
      @JsonProperty("fromRecommendation") @JsonInclude(JsonInclude.Include.NON_DEFAULT)
          boolean fromRecommendation) {

    public Item(String sku, String productId, int quantity) {
      this(sku, productId, quantity, false);
    }
  }
}
