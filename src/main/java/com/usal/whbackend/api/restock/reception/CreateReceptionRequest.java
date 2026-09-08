package com.usal.whbackend.api.restock.reception;

import com.usal.whbackend.domain.StockSize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateReceptionRequest(
    String restockOrderId,
    @NotBlank String productId,
    @Min(1) int quantityReceived,
    @NotNull StockSize deliveryUnit,
    @NotBlank String supplier,
    @NotEmpty @Valid List<AssignmentRequest> assignments) {

  public CreateReceptionRequest {
    assignments = assignments == null ? null : List.copyOf(assignments);
  }

  public record AssignmentRequest(@NotBlank String positionId, @Min(1) int quantity) {}
}
