package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.usal.whbackend.domain.Order;
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

  /** Topic the message is published on. */
  public static final String TOPIC = "order.completed";

  public OrderCompletedMessage {
    items = items == null ? List.of() : List.copyOf(items);
  }

  /**
   * The fact published when {@code order} completes. The one place the payload is built from an
   * order, shared by a real completion and by the demo seed's preload so both are identical.
   */
  public static OrderCompletedMessage from(Order order) {
    List<Item> items =
        order.getItems() == null
            ? List.of()
            : order.getItems().stream()
                .map(
                    i ->
                        new Item(
                            i.getSku(),
                            i.getProductId(),
                            i.getQuantity(),
                            i.isFromRecommendation()))
                .toList();
    return new OrderCompletedMessage(
        order.getId(),
        order.getRequestedByUserId(),
        order.getDestinationArea(),
        items,
        order.getCompletedAt() == null ? null : order.getCompletedAt().toString());
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
