package com.usal.whbackend.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReceptionTest {

  @Test
  void setAssignments_null_getAssignmentsReturnsNull() {
    Reception reception = new Reception();
    reception.setAssignments(null);
    assertNull(reception.getAssignments());
  }

  @Test
  void setAssignments_list_getAssignmentsReturnsDefensiveCopy() {
    Reception reception = new Reception();
    reception.setAssignments(List.of(new Reception.Assignment("pos-1", 10)));

    assertEquals(1, reception.getAssignments().size());
    assertEquals("pos-1", reception.getAssignments().get(0).getPositionId());
  }

  @Test
  void assignment_noArgConstructorAndSetters() {
    Reception.Assignment assignment = new Reception.Assignment();
    assignment.setPositionId("pos-1");
    assignment.setQuantity(5);

    assertEquals("pos-1", assignment.getPositionId());
    assertEquals(5, assignment.getQuantity());
  }

  @Test
  void assignment_equalsAndHashCode() {
    Reception.Assignment a = new Reception.Assignment("pos-1", 10);
    Reception.Assignment sameValues = new Reception.Assignment("pos-1", 10);
    Reception.Assignment differentQuantity = new Reception.Assignment("pos-1", 20);
    Reception.Assignment differentPosition = new Reception.Assignment("pos-2", 10);

    assertEquals(a, a);
    assertEquals(a, sameValues);
    assertEquals(a.hashCode(), sameValues.hashCode());
    assertNotEquals(a, differentQuantity);
    assertNotEquals(a, differentPosition);
    assertNotEquals(a, null);
    assertNotEquals(a, "not-an-assignment");
  }
}
