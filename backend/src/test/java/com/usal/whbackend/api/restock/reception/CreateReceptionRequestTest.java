package com.usal.whbackend.api.restock.reception;

import static org.junit.jupiter.api.Assertions.*;

import com.usal.whbackend.domain.StockSize;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreateReceptionRequestTest {

  @Test
  void constructor_nullAssignments_staysNull() {
    CreateReceptionRequest request =
        new CreateReceptionRequest(null, "p-1", 10, StockSize.PALLET, "XYZ", null);
    assertNull(request.assignments());
  }

  @Test
  void constructor_copiesAssignmentsDefensively() {
    List<CreateReceptionRequest.AssignmentRequest> assignments =
        new java.util.ArrayList<>(
            List.of(new CreateReceptionRequest.AssignmentRequest("pos-1", 5)));
    CreateReceptionRequest request =
        new CreateReceptionRequest(null, "p-1", 5, StockSize.PALLET, "XYZ", assignments);
    assignments.clear();

    assertEquals(1, request.assignments().size());
    assertEquals("pos-1", request.assignments().get(0).positionId());
    assertEquals(5, request.assignments().get(0).quantity());
  }
}
