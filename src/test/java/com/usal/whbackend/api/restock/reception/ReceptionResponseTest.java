package com.usal.whbackend.api.restock.reception;

import static org.junit.jupiter.api.Assertions.*;

import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.StockSize;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReceptionResponseTest {

  @Test
  void constructor_nullAssignments_becomesEmptyList() {
    ReceptionResponse r =
        new ReceptionResponse(
            "id-1", null, "p-1", 10, StockSize.PALLET, "XYZ", null, "user-1", Instant.now());

    assertTrue(r.assignments().isEmpty());
  }

  @Test
  void from_receptionWithNullAssignments_yieldsEmptyList() {
    Reception reception = new Reception();
    reception.setId("rcp-1");
    reception.setProductId("p-1");
    reception.setQuantityReceived(10);
    reception.setDeliveryUnit(StockSize.PALLET);
    reception.setSupplier("XYZ");
    reception.setReceivedByUserId("user-1");
    reception.setCreatedAt(Instant.now());
    // assignments left unset (null)

    ReceptionResponse r = ReceptionResponse.from(reception);

    assertTrue(r.assignments().isEmpty());
  }

  @Test
  void from_receptionWithAssignments_mapsEachOne() {
    Reception reception = new Reception();
    reception.setId("rcp-1");
    reception.setAssignments(
        java.util.List.of(
            new Reception.Assignment("pos-1", 30), new Reception.Assignment("pos-2", 18)));

    ReceptionResponse r = ReceptionResponse.from(reception);

    assertEquals(2, r.assignments().size());
    assertEquals("pos-1", r.assignments().get(0).positionId());
    assertEquals(30, r.assignments().get(0).quantity());
  }
}
