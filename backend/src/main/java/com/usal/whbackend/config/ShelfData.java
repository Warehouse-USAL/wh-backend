package com.usal.whbackend.config;

import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.ProductCategory;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.domain.User;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What only the {@code demo} seed profile knows: the three products physically on the shelves of
 * the university warehouse, the positions they sit in and the rovers that run there.
 */
final class ShelfData {

  static final String CEREAL_BAR = "CIN-001";
  static final String PLASTERS = "CUR-001";
  static final String GLUE = "GOT-001";

  /** In PA1, PA2, PA3 order. Dimensions in cm, weight in kg, price in whole pesos. */
  static final List<DemoDataset.Item> CATALOGUE =
      List.of(
          new DemoDataset.Item(
              CEREAL_BAR,
              "Barra de cereal",
              "barra de cereal felfort",
              10.0,
              10.0,
              10.0,
              0.1,
              1_800,
              1,
              15,
              "Granola_bars.jpg",
              new String[0][]),
          new DemoDataset.Item(
              PLASTERS,
              "Curitas",
              "Curitas con tela elastica comoda y flexible",
              10.0,
              20.0,
              10.0,
              0.1,
              3_000,
              1,
              30,
              "Pl%C3%A5ster_-_Adhesive_bandage_-_2025.jpg",
              new String[0][]),
          new DemoDataset.Item(
              GLUE,
              "La Gotita",
              "La gotita adhesivo instantaneo",
              10.0,
              10.0,
              10.0,
              0.1,
              2_500,
              1,
              15,
              "Super_Glue_(8560816706).jpg",
              new String[0][]));

  static final Map<String, ProductCategory> CATEGORIES =
      Map.of(
          CEREAL_BAR, ProductCategory.ALIMENTOS,
          PLASTERS, ProductCategory.OTROS,
          GLUE, ProductCategory.HERRAMIENTAS);

  /** The real layout with the shelf products on PA1-PA3; the PM positions stay empty. */
  static final List<WarehouseLayout.Slot> SLOTS =
      List.of(
          new WarehouseLayout.Slot("PA1", CEREAL_BAR, StockSize.MEDIO_PALLET),
          new WarehouseLayout.Slot("PM3", null, StockSize.PALLET),
          new WarehouseLayout.Slot("PA2", PLASTERS, StockSize.PALLET),
          new WarehouseLayout.Slot("PM1", null, StockSize.MEDIO_PALLET),
          new WarehouseLayout.Slot("PA3", GLUE, StockSize.MEDIO_PALLET),
          new WarehouseLayout.Slot("PM2", null, StockSize.MEDIO_PALLET));

  /** Infra's codes of the three rovers on site; the backend ids derive from them. */
  static final List<String> INFRA_VEHICLES = List.of("VHC-000", "VHC-005", "VHC-007");

  /**
   * About 57,000 orders in two years. The basket weights make glue the best companion of plasters,
   * cereal bar the second, and glue with cereal bar the rarest pair (the suggestions service ranks
   * by {@code cooccur / sqrt(count * count)}).
   */
  static final DemoHistory.Shape SHAPE =
      new DemoHistory.Shape(
          68,
          0,
          List.of(
              basket(18, PLASTERS),
              basket(14, GLUE),
              basket(22, CEREAL_BAR),
              basket(24, PLASTERS, GLUE),
              basket(12, PLASTERS, CEREAL_BAR),
              basket(4, GLUE, CEREAL_BAR),
              basket(6, PLASTERS, GLUE, CEREAL_BAR)),
          // The physical count on the shelves (2026-11-18).
          Map.of(CEREAL_BAR, 4, PLASTERS, 9, GLUE, 5),
          PLASTERS);

  /**
   * Accounts that only ever buy part of the catalogue. Per-user recommendations exclude what the
   * user already bought, so with three products someone has to be missing one.
   */
  private static final Map<String, Set<String>> RESTRICTED_ACCOUNTS =
      Map.of(
          "u-operator-1", Set.of(PLASTERS, GLUE),
          "u-operator-2", Set.of(PLASTERS, CEREAL_BAR),
          "u-operator-3", Set.of(GLUE));

  static List<User> accountsAllowedToBuy(List<User> users, List<OrderItem> items) {
    return users.stream()
        .filter(
            u -> {
              Set<String> allowed = RESTRICTED_ACCOUNTS.get(u.getId());
              return allowed == null || items.stream().allMatch(i -> allowed.contains(i.getSku()));
            })
        .toList();
  }

  private static DemoHistory.Basket basket(int weight, String... skus) {
    return new DemoHistory.Basket(List.of(skus), weight);
  }

  private ShelfData() {}
}
