package com.usal.whbackend.api.restock.reception;

import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.StockSize;
import java.time.Instant;
import java.util.List;

public record ReceptionResponse(
    String id,
    String restockOrderId,
    String productId,
    int quantityReceived,
    StockSize deliveryUnit,
    String supplier,
    List<AssignmentResponse> assignments,
    String receivedByUserId,
    Instant createdAt) {

  public ReceptionResponse {
    assignments = assignments == null ? List.of() : List.copyOf(assignments);
  }

  public record AssignmentResponse(String positionId, int quantity) {}

  public static ReceptionResponse from(Reception reception) {
    List<AssignmentResponse> assignments =
        reception.getAssignments() == null
            ? List.of()
            : reception.getAssignments().stream()
                .map(a -> new AssignmentResponse(a.getPositionId(), a.getQuantity()))
                .toList();

    return new ReceptionResponse(
        reception.getId(),
        reception.getRestockOrderId(),
        reception.getProductId(),
        reception.getQuantityReceived(),
        reception.getDeliveryUnit(),
        reception.getSupplier(),
        assignments,
        reception.getReceivedByUserId(),
        reception.getCreatedAt());
  }
}
