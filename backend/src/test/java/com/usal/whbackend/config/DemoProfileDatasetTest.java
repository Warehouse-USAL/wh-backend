package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleIds;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.service.PositionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The {@code demo} profile: the three products physically on the shelves. */
class DemoProfileDatasetTest {

  static final Instant NOW = Instant.parse("2026-11-27T21:00:00Z");

  private static DemoData data;

  static DemoData build(Instant now) {
    return new DemoDataset(s -> "hash:" + s, now, DemoDataset.DEFAULT_QR_PREFIX, SeedProfile.DEMO)
        .build();
  }

  @BeforeAll
  static void setUp() {
    data = build(NOW);
  }

  // ── Catalogue, warehouse, fleet ───────────────────────────────────────────

  @Test
  void theCatalogueIsExactlyTheThreeShelfProducts() {
    assertThat(data.getProducts())
        .extracting(
            Product::getSku,
            Product::getName,
            Product::getCategory,
            p -> p.getPrice().getAmountCents(),
            Product::getHeight,
            Product::getWidth,
            Product::getLength,
            Product::getWeight,
            Product::getMaxQuantityPerOrder,
            Product::getMinimumStock,
            Product::isActive)
        .containsExactly(
            tuple(
                "CIN-001",
                "Barra de cereal",
                "ALIMENTOS",
                180_000L,
                10.0,
                10.0,
                10.0,
                0.1,
                15,
                1,
                true),
            tuple("CUR-001", "Curitas", "OTROS", 300_000L, 10.0, 20.0, 10.0, 0.1, 30, 1, true),
            tuple(
                "GOT-001",
                "La Gotita",
                "HERRAMIENTAS",
                250_000L,
                10.0,
                10.0,
                10.0,
                0.1,
                15,
                1,
                true));
    assertThat(data.getProducts())
        .extracting(Product::getId)
        .containsExactly("p-CIN-001", "p-CUR-001", "p-GOT-001");
    assertThat(data.getProducts())
        .allSatisfy(
            p -> {
              assertThat(p.getDescription()).isNotBlank();
              assertThat(p.getPrice().isTaxIncluded()).isTrue();
              assertThat(p.getImages()).hasSize(1);
              assertThat(p.getImages().get(0).getUrl())
                  .startsWith("https://commons.wikimedia.org/wiki/Special:FilePath/")
                  .matches(".*\\.(jpg|jpeg|png)\\?width=\\d+")
                  .doesNotContain(" ");
            });
  }

  @Test
  void theThreeProductsSitOnPa1ToPa3AndThePmPositionsAreEmpty() {
    assertThat(data.getPositions())
        .extracting(Position::getPositionName, Position::getProductId, Position::getQrCode)
        .containsExactlyInAnyOrder(
            tuple("PA1", "p-CIN-001", "WH2|PA1"),
            tuple("PA2", "p-CUR-001", "WH2|PA2"),
            tuple("PA3", "p-GOT-001", "WH2|PA3"),
            tuple("PM1", null, "WH2|PM1"),
            tuple("PM2", null, "WH2|PM2"),
            tuple("PM3", null, "WH2|PM3"));
    assertThat(data.getZones()).hasSize(1);
    assertThat(data.getLines()).hasSize(1);
  }

  @Test
  void everyStockedPositionObeysTheVolumeRule() {
    for (Position pos : data.getPositions()) {
      if (pos.getProductId() == null) {
        continue;
      }
      Product product =
          data.getProducts().stream()
              .filter(p -> p.getId().equals(pos.getProductId()))
              .findFirst()
              .orElseThrow();
      assertThat(pos.getMaximumCapacity())
          .isPositive()
          .isLessThanOrEqualTo(
              PositionService.maxUnitsByVolume(
                  product.getVolume(), pos.getSizeStockToSave().getVolumeCm3()));
      assertThat(pos.getCurrentStock()).isBetween(0, pos.getMaximumCapacity());
    }
  }

  @Test
  void theFleetIsTheThreeRoversInfraReports() {
    assertThat(data.getVehicles())
        .extracting(Vehicle::getName, Vehicle::getId, Vehicle::getStatus)
        .containsExactly(
            tuple("VHC-000", VehicleIds.fromInfra("VHC-000"), VehicleStatus.OFFLINE),
            tuple("VHC-005", VehicleIds.fromInfra("VHC-005"), VehicleStatus.OFFLINE),
            tuple("VHC-007", VehicleIds.fromInfra("VHC-007"), VehicleStatus.OFFLINE));
    // The ids the live server shows for its three rovers.
    assertThat(data.getVehicles())
        .extracting(Vehicle::getId)
        .containsExactly(
            "42952f2d-fa40-52d3-a542-b5092b95b0cf",
            "97613444-138c-5182-be2b-a45ebea4558b",
            "743f67ab-9231-5574-8f47-c53099360e32");
  }

  @Test
  void theAccountsAreTheSameAsTheFullProfile() {
    List<User> full = new DemoDataset(s -> "hash:" + s, NOW).build().getUsers();
    assertThat(data.getUsers())
        .extracting(User::getId, User::getEmail, User::getRole)
        .containsExactlyElementsOf(
            full.stream().map(u -> tuple(u.getId(), u.getEmail(), u.getRole())).toList());
  }
}
