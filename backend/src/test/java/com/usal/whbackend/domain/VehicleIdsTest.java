package com.usal.whbackend.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleIdsTest {

  @Test
  void uuidV5_matchesTheRfc4122ReferenceVector() {
    UUID dnsNamespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    assertEquals(
        "886313e1-3b8a-5372-9b90-0c9aee199e5d",
        VehicleIds.uuidV5(dnsNamespace, "python.org").toString());
  }

  @Test
  void fromInfra_isDeterministicAndDistinguishesVehicles() {
    assertEquals(VehicleIds.fromInfra("VHC-001"), VehicleIds.fromInfra("VHC-001"));
    assertNotEquals(VehicleIds.fromInfra("VHC-001"), VehicleIds.fromInfra("VHC-002"));
  }

  @Test
  void fromInfra_ignoresSurroundingWhitespace() {
    assertEquals(VehicleIds.fromInfra("VHC-001"), VehicleIds.fromInfra(" VHC-001 "));
  }
}
