package com.usal.whbackend.config;

import com.usal.whbackend.domain.Address;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderPriority;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.ProductCategory;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.domain.Zone;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Builds a complete, internally-consistent "production-looking" demo dataset entirely in memory.
 *
 * <p>Pure by design: it has no Spring or Mongo dependency and takes a password hasher as a
 * parameter, so it can be unit-tested directly. Every entity gets a deterministic String id, so all
 * cross-references (orders → users/products/vehicles, positions → products, vehicle ↔ order) are
 * wired in memory and the seeder only has to persist the lists.
 *
 * <p>Stock is produced causally: initial inbound stock is placed into positions, then COMPLETED
 * orders draw it down FIFO (oldest position first, mirroring {@code StockDrainService}) — without
 * publishing any events. PENDING/IN_PROGRESS orders are left to be counted as reserved by the app.
 */
public final class DemoDataset {

  /** Shared password for every seeded demo account. */
  public static final String SHARED_PASSWORD = "Demo1234!";

  private static final String EMAIL_DOMAIN = "@smartwarehouse.local";
  private static final String CURRENCY = "UYU";

  /** Units left available (above reservations) for each product after completed orders drain. */
  private static final int HEALTHY_BUFFER = 30;

  private static final int PRODUCT_POSITIONS = 32;
  private static final int POSITIONS_PER_LINE = 5;
  private static final int POSITION_MAX_CAPACITY = 500;

  // How far back the historical order batch reaches, so the dashboard has a full year to chart
  // instead of the ~3 weeks the near-term batch alone covers. Starts after the near-term window
  // (which reaches back 13 days) rather than at day 1, so the two batches do not overlap.
  private static final int HISTORICAL_START_DAY = 14;
  private static final int HISTORICAL_END_DAY = 365;
  private static final int HISTORICAL_ORDERS_PER_DAY = 2;

  // Cadence for the restock/reception history: one request-and-delivery pair every N days,
  // cycling through every product round-robin, so the stock-movements chart has a year of
  // stock-IN events to plot alongside the orders' stock-OUT signal.
  private static final int RECEPTION_INTERVAL_DAYS = 5;
  private static final String[] SUPPLIERS = {
    "Distribuidora del Sur S.A.", "Importadora Andina Ltda.", "Mayorista Rioplatense"
  };

  private static final String[] DESTINATIONS = {
    "Depósito Central",
    "Sucursal Pocitos",
    "Sucursal Centro",
    "Planta Industrial",
    "Sucursal Carrasco"
  };
  private static final String[] STREETS = {
    "Av. 18 de Julio 1234",
    "Bvar. Artigas 567",
    "Av. Italia 4321",
    "Rambla República del Perú 800",
    "Av. Brasil 2890",
    "Calle Canelones 1500"
  };

  private final UnaryOperator<String> passwordHasher;
  private final Instant now = Instant.now();

  public DemoDataset(UnaryOperator<String> passwordHasher) {
    this.passwordHasher = passwordHasher;
  }

  /** Builds the full dataset. Deterministic except for absolute timestamps (anchored to now). */
  public DemoData build() {
    List<User> users = buildUsers();
    List<Product> products = buildProducts();
    List<Zone> zones = new ArrayList<>();
    List<Line> lines = new ArrayList<>();
    List<Position> positions = new ArrayList<>();
    buildWarehouse(products, zones, lines, positions);
    List<Vehicle> vehicles = buildVehicles();
    List<Order> orders = buildOrders(users, products, vehicles);
    orders.addAll(buildHistoricalOrders(users, products, vehicles, orders.size()));
    simulateStock(products, positions, orders);
    List<RestockOrder> restockOrders = new ArrayList<>();
    List<Reception> receptions = new ArrayList<>();
    buildReceptionHistory(products, positions, restockOrders, receptions);
    return new DemoData(
        users, products, zones, lines, positions, vehicles, orders, restockOrders, receptions);
  }

  // ── Users ────────────────────────────────────────────────────────────────────

  private List<User> buildUsers() {
    List<User> users = new ArrayList<>();
    users.add(user("u-system", "system", "Sofía Martínez", UserRole.ADMIN_SYSTEM, 60));
    users.add(user("u-warehouse", "warehouse", "Diego Fernández", UserRole.ADMIN_WAREHOUSE, 58));
    users.add(user("u-sales", "sales", "Valentina Rodríguez", UserRole.ADMIN_SALES, 55));
    users.add(
        user("u-provider-1", "provider1", "Distribuidora del Sur S.A.", UserRole.PROVIDER, 50));
    users.add(user("u-provider-2", "provider2", "Importadora Andina Ltda.", UserRole.PROVIDER, 48));
    users.add(user("u-dispatcher-1", "dispatcher1", "Lucas Pérez", UserRole.DISPATCHER, 45));
    users.add(user("u-dispatcher-2", "dispatcher2", "Martín Gómez", UserRole.DISPATCHER, 43));
    users.add(user("u-operator-1", "operator1", "Camila Suárez", UserRole.OPERATOR, 40));
    users.add(user("u-operator-2", "operator2", "Bruno Castro", UserRole.OPERATOR, 38));
    users.add(user("u-operator-3", "operator3", "Lucía Méndez", UserRole.OPERATOR, 35));
    users.add(user("u-operator-4", "operator4", "Joaquín Silva", UserRole.OPERATOR, 33));
    users.add(user("u-operator-5", "operator5", "Florencia Díaz", UserRole.OPERATOR, 30));
    // The account Grupo 3's dashboard authenticates as. Read-only by construction: DASHBOARD
    // appears in no write endpoint, so a demo cannot be damaged by whatever the dashboard does.
    users.add(user("u-dashboard", "dashboard", "Panel de Monitoreo", UserRole.DASHBOARD, 20));
    return users;
  }

  private User user(String id, String localPart, String name, UserRole role, int daysAgo) {
    User u = new User();
    u.setId(id);
    u.setEmail(localPart + EMAIL_DOMAIN);
    u.setName(name);
    u.setRole(role);
    u.setActive(true);
    u.setPasswordHash(passwordHasher.apply(SHARED_PASSWORD));
    u.setCreatedAt(now.minus(daysAgo, ChronoUnit.DAYS));
    u.setAddress(montevideoAddress(id.hashCode()));
    return u;
  }

  // ── Products ─────────────────────────────────────────────────────────────────

  private List<Product> buildProducts() {
    List<Product> products = new ArrayList<>();
    addCategory(
        products,
        ProductCategory.TECNOLOGIA,
        "TEC",
        new String[][] {
          {"Notebook Lenovo ThinkPad E14", "Portátil 14\" Intel i5, 16GB RAM, 512GB SSD"},
          {"Monitor LG UltraGear 27\"", "Monitor 27 pulgadas 144Hz Full HD"},
          {"Teclado mecánico Redragon", "Teclado mecánico retroiluminado switches red"},
          {"Mouse inalámbrico Logitech M720", "Mouse ergonómico Bluetooth multidispositivo"},
          {"Auriculares Sony WH-1000XM4", "Auriculares inalámbricos con cancelación de ruido"},
          {"Webcam Logitech C920 HD", "Cámara web Full HD 1080p con micrófono"}
        });
    addCategory(
        products,
        ProductCategory.HERRAMIENTAS,
        "HER",
        new String[][] {
          {"Taladro percutor Bosch 750W", "Taladro percutor con maletín y accesorios"},
          {"Amoladora angular DeWalt 4.5\"", "Amoladora angular 850W para corte y desbaste"},
          {"Set destornilladores Stanley x12", "Juego de 12 destornilladores de precisión"},
          {"Martillo carpintero 16oz", "Martillo de uña con mango de fibra de vidrio"},
          {"Llave ajustable Bahco 10\"", "Llave inglesa ajustable cromada"},
          {"Caja de herramientas metálica", "Caja organizadora de 5 compartimentos"}
        });
    addCategory(
        products,
        ProductCategory.ALIMENTOS,
        "ALI",
        new String[][] {
          {"Arroz blanco 1kg", "Arroz largo fino tipo 00000"},
          {"Aceite de girasol 900ml", "Aceite vegetal de girasol"},
          {"Yerba mate 1kg", "Yerba mate con palo tradicional"},
          {"Harina 0000 1kg", "Harina de trigo refinada"},
          {"Azúcar blanca 1kg", "Azúcar refinada"},
          {"Fideos secos 500g", "Fideos tipo spaghetti"}
        });
    addCategory(
        products,
        ProductCategory.OTROS,
        "OTR",
        new String[][] {
          {"Resma papel A4 500 hojas", "Papel blanco 75g/m² para impresión"},
          {"Silla de oficina ergonómica", "Silla giratoria con apoyabrazos regulable"},
          {"Cuaderno tapa dura A4", "Cuaderno rayado 100 hojas"},
          {"Caja de archivo cartón", "Caja organizadora de documentos"},
          {"Cinta de embalar transparente", "Rollo de cinta adhesiva 48mm x 100m"},
          {"Bolígrafos azules x12", "Caja de 12 bolígrafos de tinta azul"}
        });
    return products;
  }

  private void addCategory(
      List<Product> products, ProductCategory category, String prefix, String[][] entries) {
    for (int i = 0; i < entries.length; i++) {
      int globalIdx = products.size();
      String sku = prefix + String.format("-%03d", i + 1);
      products.add(product(globalIdx, category, sku, entries[i][0], entries[i][1]));
    }
  }

  private Product product(
      int globalIdx, ProductCategory category, String sku, String name, String description) {
    Product p = new Product();
    p.setId("p-" + sku);
    p.setSku(sku);
    p.setName(name);
    p.setDescription(description);
    p.setCategory(category.name());
    p.setActive(true);
    p.setCreatedAt(now.minus(35L - globalIdx, ChronoUnit.DAYS));

    Product.Price price = new Product.Price();
    price.setAmountCents(50_000L + category.ordinal() * 100_000L + globalIdx * 25_000L);
    price.setCurrency(CURRENCY);
    price.setTaxIncluded(true);
    p.setPrice(price);

    Product.ProductImage image = new Product.ProductImage();
    image.setUrl("https://picsum.photos/seed/" + sku + "/600/400");
    image.setAlt(name);
    image.setPrimary(true);
    p.setImages(List.of(image));

    Product.Spec origin = new Product.Spec();
    origin.setLabel("Origen");
    origin.setValue("Importado");
    Product.Spec warranty = new Product.Spec();
    warranty.setLabel("Garantía");
    warranty.setValue("12 meses");
    p.setSpecs(List.of(origin, warranty));

    p.setHeight(10.0 + globalIdx);
    p.setWidth(20.0 + globalIdx);
    p.setLength(30.0 + globalIdx);
    p.setWeight(1.0 + globalIdx * 0.5);
    // Product 0 is intentionally left below its minimum stock for a realistic "low stock" demo.
    p.setMinimumStock(globalIdx == 0 ? 50 : 10);
    p.setMaxQuantityPerOrder(20 + (globalIdx % 5) * 10);
    return p;
  }

  // ── Warehouse (zones → lines → positions) ──────────────────────────────────────

  private void buildWarehouse(
      List<Product> products, List<Zone> zones, List<Line> lines, List<Position> positions) {
    Zone zoneB = zone("z-B", "B");
    Zone zoneC = zone("z-C", "C");
    zones.add(zoneB);
    zones.add(zoneC);

    List<Line> demoLines = new ArrayList<>();
    for (int i = 1; i <= 4; i++) {
      demoLines.add(line("l-B-" + i, zoneB.getId(), i));
    }
    for (int i = 1; i <= 3; i++) {
      demoLines.add(line("l-C-" + i, zoneC.getId(), i));
    }
    lines.addAll(demoLines);

    int globalIdx = 0;
    for (Line ln : demoLines) {
      for (int pi = 1; pi <= POSITIONS_PER_LINE; pi++) {
        String name = String.format("P%02d", pi);
        String posId = "pos-" + ln.getId() + "-" + name;
        Instant created = now.minus(40L - globalIdx, ChronoUnit.DAYS);
        String productId =
            globalIdx < PRODUCT_POSITIONS
                ? products.get(globalIdx % products.size()).getId()
                : null;
        positions.add(
            position(posId, ln.getId(), ln.getIdZone(), name, globalIdx, created, productId));
        globalIdx++;
      }
    }
  }

  private Zone zone(String id, String code) {
    Zone z = new Zone();
    z.setId(id);
    z.setZoneCode(code);
    z.setActive(true);
    z.setMaxAllowedLines(10);
    z.setCreatedAt(now.minus(45, ChronoUnit.DAYS));
    return z;
  }

  private Line line(String id, String idZone, int number) {
    Line l = new Line();
    l.setId(id);
    l.setIdZone(idZone);
    l.setNumberLine(number);
    l.setActive(true);
    l.setMaxAllowedPositions(20);
    l.setCreatedAt(now.minus(44, ChronoUnit.DAYS));
    return l;
  }

  private Position position(
      String id,
      String idLine,
      String idZone,
      String name,
      int sizeIdx,
      Instant createdAt,
      String productId) {
    Position p = new Position();
    p.setId(id);
    p.setIdLine(idLine);
    p.setIdZone(idZone);
    p.setPositionName(name);
    p.setActive(true);
    p.setMaximumCapacity(POSITION_MAX_CAPACITY);
    StockSize[] sizes = StockSize.values();
    p.setSizeStockToSave(sizes[Math.floorMod(sizeIdx, sizes.length)]);
    p.setProductId(productId);
    p.setCurrentStock(0);
    p.setCreatedAt(createdAt);
    return p;
  }

  // ── Vehicles ─────────────────────────────────────────────────────────────────

  private List<Vehicle> buildVehicles() {
    List<Vehicle> vehicles = new ArrayList<>();
    vehicles.add(vehicle("veh-agv-01", "AGV-01", VehicleStatus.IDLE, 12.5, 4.0, 96, 3, 45L));
    vehicles.add(vehicle("veh-agv-02", "AGV-02", VehicleStatus.IDLE, 30.0, 8.0, 88, 5, 120L));
    vehicles.add(vehicle("veh-agv-03", "AGV-03", VehicleStatus.BUSY, 18.0, 15.0, 64, 1, 10L));
    vehicles.add(vehicle("veh-agv-04", "AGV-04", VehicleStatus.BUSY, 42.0, 22.0, 57, 1, 200L));
    // OFFLINE and ERROR both null out operationSince in the live system (VehicleTelemetryConsumer
    // and VehicleErrorConsumer clear it on any transition into either state) — neither has an
    // ongoing operation window to report, so neither gets one here.
    vehicles.add(vehicle("veh-agv-05", "AGV-05", VehicleStatus.OFFLINE, 0.0, 0.0, 0, 720, null));
    vehicles.add(vehicle("veh-agv-06", "AGV-06", VehicleStatus.ERROR, 25.0, 10.0, 12, 90, null));
    return vehicles;
  }

  private Vehicle vehicle(
      String id,
      String name,
      VehicleStatus status,
      double x,
      double y,
      int battery,
      long minAgo,
      Long operationSinceDaysAgo) {
    Vehicle v = new Vehicle();
    v.setId(id);
    v.setName(name);
    v.setStatus(status);
    v.setPositionX(x);
    v.setPositionY(y);
    v.setBattery(battery);
    v.setLastSeenAt(now.minus(minAgo, ChronoUnit.MINUTES));
    if (operationSinceDaysAgo != null) {
      v.setOperationSince(now.minus(operationSinceDaysAgo, ChronoUnit.DAYS));
    }
    return v;
  }

  // ── Orders (wires vehicle ↔ order for active deliveries) ────────────────────────

  private List<Order> buildOrders(
      List<User> users, List<Product> products, List<Vehicle> vehicles) {
    List<Order> orders = new ArrayList<>();
    int n = 0;
    for (int i = 0; i < 7; i++) {
      orders.add(makeOrder(++n, OrderStatus.PENDING, users, products, null, 1 + i, null));
    }
    orders.add(makeOrder(++n, OrderStatus.IN_PROGRESS, users, products, vehicles.get(2), 2, null));
    orders.add(makeOrder(++n, OrderStatus.IN_PROGRESS, users, products, vehicles.get(3), 1, null));
    for (int i = 0; i < 13; i++) {
      orders.add(
          makeOrder(
              ++n, OrderStatus.COMPLETED, users, products, vehicles.get(i % 6), 10 + i, null));
    }
    String[] reasons = {
      "Stock insuficiente", "Cancelado por el cliente", "Dirección de entrega incorrecta"
    };
    for (int i = 0; i < 3; i++) {
      orders.add(makeOrder(++n, OrderStatus.CANCELLED, users, products, null, 6 + i, reasons[i]));
    }
    return orders;
  }

  private Order makeOrder(
      int n,
      OrderStatus status,
      List<User> users,
      List<Product> products,
      Vehicle vehicle,
      int daysAgo,
      String cancelReason) {
    return makeOrder(
        n, status, users, products, vehicle, now.minus(daysAgo, ChronoUnit.DAYS), cancelReason);
  }

  private Order makeOrder(
      int n,
      OrderStatus status,
      List<User> users,
      List<Product> products,
      Vehicle vehicle,
      Instant created,
      String cancelReason) {
    Order o = new Order();
    String id = String.format("o-%04d", n);
    o.setId(id);
    o.setStatus(status);
    o.setPriority(OrderPriority.values()[Math.floorMod(n, OrderPriority.values().length)]);
    o.setRequestedByUserId(users.get(n % users.size()).getId());
    o.setItems(buildItems(n, products));
    o.setDestinationArea(DESTINATIONS[n % DESTINATIONS.length]);
    o.setAddress(montevideoAddress(n * 7));
    o.setCreatedAt(created);

    switch (status) {
      case IN_PROGRESS -> {
        o.setStartedAt(created.plus(2, ChronoUnit.HOURS));
        o.setAssignedVehicleId(vehicle.getId());
        vehicle.setCurrentOrderId(id);
      }
      case COMPLETED -> {
        // Jittered by n rather than a fixed 3h/5h pair, so a year of history has real cycle-time
        // variance instead of every completed order taking exactly the same 5 hours.
        Instant started = created.plus(1 + Math.floorMod(n, 4), ChronoUnit.HOURS);
        o.setStartedAt(started);
        o.setCompletedAt(started.plus(1 + Math.floorMod(n * 3, 6), ChronoUnit.HOURS));
        o.setAssignedVehicleId(vehicle.getId());
      }
      case CANCELLED -> o.setCancelReason(cancelReason);
      default -> {
        // PENDING: no vehicle, no timestamps beyond createdAt.
      }
    }
    return o;
  }

  // ── Historical orders (past year, COMPLETED/CANCELLED only) ────────────────────

  /**
   * Extends the near-term batch built by {@link #buildOrders} back across a full year, so charts
   * bucketed by day or hour have real history instead of ~3 weeks of data next to a 365-day axis.
   * Deliberately COMPLETED/CANCELLED only: a year-old PENDING or IN_PROGRESS order would be a
   * standing bug in any real system, so seeding one would be seeding a lie.
   */
  private List<Order> buildHistoricalOrders(
      List<User> users, List<Product> products, List<Vehicle> vehicles, int startIndex) {
    List<Order> orders = new ArrayList<>();
    String[] reasons = {
      "Stock insuficiente", "Cancelado por el cliente", "Dirección de entrega incorrecta"
    };
    int n = startIndex;
    for (int daysAgo = HISTORICAL_START_DAY; daysAgo <= HISTORICAL_END_DAY; daysAgo++) {
      for (int slot = 0; slot < HISTORICAL_ORDERS_PER_DAY; slot++) {
        n++;
        boolean cancelled = n % 7 == 0;
        OrderStatus status = cancelled ? OrderStatus.CANCELLED : OrderStatus.COMPLETED;
        Vehicle vehicle = cancelled ? null : vehicles.get(n % vehicles.size());
        String reason = cancelled ? reasons[n % reasons.length] : null;
        int hourOfDay = (n * 7) % 24;
        Instant created =
            now.minus(daysAgo, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS)
                .plus(hourOfDay, ChronoUnit.HOURS);
        orders.add(makeOrder(n, status, users, products, vehicle, created, reason));
      }
    }
    return orders;
  }

  // ── Restock/reception history (past year, stock-IN events) ─────────────────────

  /**
   * A restock order + linked reception every {@link #RECEPTION_INTERVAL_DAYS}, cycling through
   * every product, so the stock-movements chart (#54/#55) has a year of stock-IN events. Every
   * fifth reception omits the {@code restockOrderId} link, matching {@link Reception}'s real
   * contract: a reception need not reference an order.
   *
   * <p>Deliberately does not touch {@link Position#getCurrentStock()} — {@link #simulateStock}
   * already derives a self-consistent stock picture from orders alone, and reconciling that
   * simulation against an independent reception history is not needed for what this seeds the data
   * for: exercising the {@code /query/receptions} chart with plausible stock-IN events.
   */
  private void buildReceptionHistory(
      List<Product> products,
      List<Position> positions,
      List<RestockOrder> restockOrders,
      List<Reception> receptions) {
    int n = 0;
    for (int daysAgo = HISTORICAL_END_DAY;
        daysAgo >= RECEPTION_INTERVAL_DAYS;
        daysAgo -= RECEPTION_INTERVAL_DAYS) {
      n++;
      Product product = products.get(n % products.size());
      Position position =
          positions.stream()
              .filter(p -> product.getId().equals(p.getProductId()))
              .findFirst()
              .orElse(null);
      if (position == null) {
        continue;
      }

      int quantity = 40 + (n % 6) * 20;
      Instant requestedAt = now.minus(daysAgo + 1, ChronoUnit.DAYS);
      Instant receivedAt = now.minus(daysAgo, ChronoUnit.DAYS);
      String supplier = SUPPLIERS[n % SUPPLIERS.length];

      RestockOrder restockOrder = new RestockOrder();
      restockOrder.setId(String.format("ro-%04d", n));
      restockOrder.setProductId(product.getId());
      restockOrder.setQuantityRequested(quantity);
      restockOrder.setSupplier(supplier);
      restockOrder.setRequestedByUserId("u-warehouse");
      restockOrder.setCreatedAt(requestedAt);
      restockOrders.add(restockOrder);

      boolean linked = n % 5 != 0;

      Reception reception = new Reception();
      reception.setId(String.format("rec-%04d", n));
      reception.setRestockOrderId(linked ? restockOrder.getId() : null);
      reception.setProductId(product.getId());
      reception.setQuantityReceived(quantity);
      reception.setDeliveryUnit(StockSize.values()[n % StockSize.values().length]);
      reception.setSupplier(supplier);
      reception.setAssignments(List.of(new Reception.Assignment(position.getId(), quantity)));
      reception.setReceivedByUserId("u-warehouse");
      reception.setCreatedAt(receivedAt);
      receptions.add(reception);
    }
  }

  private List<OrderItem> buildItems(int n, List<Product> products) {
    int count = 1 + (n % 3);
    List<OrderItem> items = new ArrayList<>();
    for (int k = 0; k < count; k++) {
      // Skip product index 0 — it is the intentionally low-stock, never-ordered item.
      Product p = products.get(1 + Math.floorMod(n + k, products.size() - 1));
      int qty = Math.min(1 + Math.floorMod(n + k, 6), p.getMaxQuantityPerOrder());
      items.add(new OrderItem(p.getId(), p.getSku(), qty));
    }
    return items;
  }

  // ── Causal stock simulation ─────────────────────────────────────────────────────

  private void simulateStock(List<Product> products, List<Position> positions, List<Order> orders) {
    Map<String, List<Position>> byProduct =
        positions.stream()
            .filter(p -> p.getProductId() != null)
            .collect(Collectors.groupingBy(Position::getProductId));
    byProduct.values().forEach(list -> list.sort(Comparator.comparing(Position::getCreatedAt)));

    Map<String, Integer> completed = sumQuantities(orders, EnumSet.of(OrderStatus.COMPLETED));
    Map<String, Integer> reserved =
        sumQuantities(orders, EnumSet.of(OrderStatus.PENDING, OrderStatus.IN_PROGRESS));

    // Place initial inbound stock so each product can cover its completed + reserved demand +
    // buffer.
    for (Product p : products) {
      List<Position> hosts = byProduct.get(p.getId());
      if (hosts == null) {
        continue;
      }
      int total =
          completed.getOrDefault(p.getId(), 0)
              + reserved.getOrDefault(p.getId(), 0)
              + HEALTHY_BUFFER;
      distributeStock(total, hosts);
    }

    // Draw down stock for completed orders, FIFO (oldest position first).
    for (Order o : orders) {
      if (o.getStatus() != OrderStatus.COMPLETED) {
        continue;
      }
      for (OrderItem item : o.getItems()) {
        List<Position> hosts = byProduct.get(item.getProductId());
        if (hosts != null) {
          drainFifo(hosts, item.getQuantity());
        }
      }
    }
  }

  private void distributeStock(int total, List<Position> hosts) {
    int base = total / hosts.size();
    int remainder = total % hosts.size();
    for (int i = 0; i < hosts.size(); i++) {
      hosts.get(i).setCurrentStock(base + (i < remainder ? 1 : 0));
    }
  }

  private void drainFifo(List<Position> hosts, int quantity) {
    int remaining = quantity;
    for (Position pos : hosts) {
      if (remaining <= 0) {
        break;
      }
      int drained = Math.min(remaining, pos.getCurrentStock());
      pos.setCurrentStock(pos.getCurrentStock() - drained);
      remaining -= drained;
    }
  }

  private Map<String, Integer> sumQuantities(List<Order> orders, Set<OrderStatus> statuses) {
    return orders.stream()
        .filter(o -> statuses.contains(o.getStatus()))
        .flatMap(o -> o.getItems().stream())
        .collect(
            Collectors.groupingBy(
                OrderItem::getProductId, Collectors.summingInt(OrderItem::getQuantity)));
  }

  // ── Shared helpers ───────────────────────────────────────────────────────────

  private Address montevideoAddress(int seed) {
    Address a = new Address();
    a.setStreet(STREETS[Math.floorMod(seed, STREETS.length)]);
    a.setDepartment("Montevideo");
    a.setFloor(String.valueOf(1 + Math.floorMod(seed, 9)));
    a.setPostalCode("112" + String.format("%02d", Math.floorMod(seed, 100)));
    return a;
  }
}
