package com.usal.whbackend.api.restock.reception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record AssignReceptionPositionsRequest(
    @NotEmpty @Valid List<CreateReceptionRequest.AssignmentRequest> assignments) {

  public AssignReceptionPositionsRequest {
    assignments = assignments == null ? null : List.copyOf(assignments);
  }
}
