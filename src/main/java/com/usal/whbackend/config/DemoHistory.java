package com.usal.whbackend.config;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.ProductCategory;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.ReceptionStatus;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.service.metrics.restock.RestockFormula;
import com.usal.whbackend.service.metrics.restock.RestockInputs;
import com.usal.whbackend.service.metrics.restock.RestockParams;
import com.usal.whbackend.service.metrics.restock.RestockResult;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Two years of order and restock history, simulated causally one day at a time so every chart that
 * reads it sees realistic, mutually consistent data (RFC_Metricas_Calculadas.md §7).
 *
 * <p>Each day: supplier deliveries arrive in the morning, then the day's orders are processed in
 * time order — an order whose items are not all in stock is cancelled as "Stock insuficiente" — and
 * every working day a simulated buyer reviews every product with the restock formula and the same
 * params the daily {@code restock-cron} uses, ordering what it suggests. So stock never goes
 * negative, physical stock always equals what was put away minus what shipped, and restocks visibly
 * follow demand.
 *
 * <p>Demand has a working-hours profile, a weekly cycle, per-category annual seasonality (peak in
 * November–December, trough in January–February), commercial spikes (Hot Sale, Black Friday, Cyber
 * Monday, Christmas), per-product promotions and ~15% year-over-year growth.
 *
 * <p>The end state is then shaped so the restock metric shows every case with the demo params —
 * restock now, covered by stock in transit, healthy — through ordinary ledger events (a large
 * customer order, a fresh purchase order, a top-up delivery), never by editing stock directly.
 */
final class DemoHistory {

  /** Same zone as the fleet telemetry seed, so both histories share one calendar. */
  static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

  static final int HISTORY_DAYS = 730;

  /** Turns a simulation decision into an {@link Order}; owned by {@link DemoDataset}. */
  interface OrderMaker {
    Order make(
        int n,
        OrderStatus status,
        List<OrderItem> items,
        Vehicle vehicle,
        Instant created,
        String cancelReason);
  }

  record Result(List<Order> orders, List<RestockOrder> restockOrders, List<Reception> receptions) {}

  private static final long SEED = 20_261_004L;
  private static final int BASE_ORDERS_PER_DAY = 68;
  private static final double YEARLY_GROWTH = 0.15;
  private static final double CUSTOMER_CANCEL_RATE = 0.03;
  private static final double PARTIAL_DELIVERY_RATE = 0.12;
  private static final int MIN_PURCHASE = 5;
  private static final int OPENING_STOCK = 250;
  private static final int NEVER_ORDERED_STOCK = 30;
  private static final String STOCKOUT = "Stock insuficiente";
  private static final String[] CUSTOMER_REASONS = {
    "Cancelado por el cliente", "Dirección de entrega incorrecta"
  };
  private static final String[] SUPPLIERS = {
    "Distribuidora del Sur S.A.", "Importadora Andina Ltda.", "Mayorista Rioplatense"
  };

  // Monday..Sunday.
  private static final double[] WEEKLY = {1.10, 1.12, 1.12, 1.08, 1.18, 0.68, 0.38};
  // January..December, whole warehouse.
  private static final double[] MONTHLY = {
    0.72, 0.76, 0.95, 1.00, 1.02, 0.96, 1.08, 1.00, 1.00, 1.04, 1.22, 1.38
  };
  // Working hours 08..19, peaking mid-morning and mid-afternoon.
  private static final int[] HOUR_WEIGHTS = {3, 6, 9, 10, 8, 5, 6, 8, 9, 7, 5, 3};
  private static final int FIRST_HOUR = 8;

  private final Instant now;
  private final LocalDate today;
  private final RestockParams policy;
  private final List<Product> products;
  private final Map<String, List<Position>> positionsByProduct;
  private final List<Vehicle> vehicles;
  private final OrderMaker maker;
  private final Random random = new Random(SEED);

  private final int[] stock;
  private final int[] reserved;
  private final int[] capacity;
  // Non-cancelled units ordered per product, indexed by days ago — the demand the buyer reads.
  private final int[][] units;
  private final double[] popularity;
  private final List<Set<LocalDate>> promoDays;
  private final Map<String, Integer> indexById = new HashMap<>();

  private final List<Order> orders = new ArrayList<>();
  private final List<RestockOrder> restockOrders = new ArrayList<>();
  private final List<Reception> receptions = new ArrayList<>();
  private final List<OpenOrder> open = new ArrayList<>();
  private final Map<Integer, List<Delivery>> deliveriesByDay = new HashMap<>();
  private final Map<String, Integer> receptionsPerProduct = new HashMap<>();
  private int orderNumber;

  /** A purchase order still expecting units. */
  private static final class OpenOrder {
    final RestockOrder order;
    final int productIdx;
    int remaining;

    OpenOrder(RestockOrder order, int productIdx) {
      this.order = order;
      this.productIdx = productIdx;
      this.remaining = order.getQuantityRequested();
    }
  }

  private record Delivery(OpenOrder order, int quantity) {}

  private record Intent(Instant created, List<OrderItem> items, Order fixed) {}

  DemoHistory(
      Instant now,
      RestockParams policy,
      List<Product> products,
      List<Position> positions,
      List<Vehicle> vehicles,
      OrderMaker maker) {
    this.now = now;
    this.today = LocalDate.ofInstant(now, ZONE);
    this.policy = policy;
    this.products = products;
    this.vehicles = vehicles;
    this.maker = maker;
    this.positionsByProduct = new HashMap<>();
    for (Position pos : positions) {
      if (pos.getProductId() != null) {
        positionsByProduct.computeIfAbsent(pos.getProductId(), k -> new ArrayList<>()).add(pos);
      }
    }
    positionsByProduct.values().forEach(l -> l.sort(Comparator.comparing(Position::getCreatedAt)));

    int n = products.size();
    stock = new int[n];
    reserved = new int[n];
    capacity = new int[n];
    units = new int[n][HISTORY_DAYS + 2];
    popularity = new double[n];
    promoDays = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Product p = products.get(i);
      indexById.put(p.getId(), i);
      capacity[i] =
          positionsByProduct.getOrDefault(p.getId(), List.of()).stream()
              .mapToInt(Position::getMaximumCapacity)
              .sum();
      // Products with two positions are the best sellers; the rest spread over 0.6–1.4.
      popularity[i] =
          (0.6 + Math.floorMod(i * 7, 9) / 10.0)
              * (positionsByProduct.getOrDefault(p.getId(), List.of()).size() > 1 ? 1.4 : 1.0);
      promoDays.add(promotions());
    }
  }

  /**
   * @param nearTerm the fixed recent orders (pending, in progress…) that wire vehicles; they take
   *     part in the stock simulation but keep their status
   * @param lastOrderNumber the highest order number already used by {@code nearTerm}
   */
  Result simulate(List<Order> nearTerm, int lastOrderNumber) {
    orderNumber = lastOrderNumber;
    Map<Integer, List<Order>> nearTermByDay = new HashMap<>();
    for (Order o : nearTerm) {
      nearTermByDay.computeIfAbsent(daysAgo(o.getCreatedAt()), k -> new ArrayList<>()).add(o);
    }

    openingStock();
    for (int d = HISTORY_DAYS; d >= 1; d--) {
      deliver(d);
      processOrders(d, nearTermByDay.getOrDefault(d, List.of()));
      DayOfWeek dow = dateOf(d).getDayOfWeek();
      if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
        review(d);
      }
    }
    // Near-term orders created today (none today, but keep the simulation total).
    processOrders(0, nearTermByDay.getOrDefault(0, List.of()));
    shapeEndState();
    return new Result(orders, restockOrders, receptions);
  }

  // ── Calendar ─────────────────────────────────────────────────────────────────

  static LocalDate blackFriday(int year) {
    return LocalDate.of(year, 11, 1)
        .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
        .plusDays(1);
  }

  private static double spike(LocalDate date) {
    LocalDate bf = blackFriday(date.getYear());
    if (date.equals(bf)) {
      return 2.6;
    }
    if (date.equals(bf.plusDays(3))) {
      return 2.0; // Cyber Monday
    }
    LocalDate hotSale =
        LocalDate.of(date.getYear(), 5, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.MONDAY));
    if (!date.isBefore(hotSale) && date.isBefore(hotSale.plusDays(3))) {
      return 1.8;
    }
    if (date.getMonthValue() == 12 && date.getDayOfMonth() >= 15 && date.getDayOfMonth() <= 23) {
      return 1.4;
    }
    return 1.0;
  }

  private static double categorySeason(String category, int month) {
    double[] table =
        switch (ProductCategory.valueOf(category)) {
          case TECNOLOGIA -> new double[] {0.9, 0.9, 1, 1, 1.05, 1, 1.1, 1, 0.95, 1, 1.25, 1.35};
          case HERRAMIENTAS ->
              new double[] {0.85, 0.9, 1, 1, 0.95, 0.85, 0.85, 1, 1.15, 1.2, 1.15, 1};
          // Staples barely follow the warehouse's seasonality.
          case ALIMENTOS -> new double[] {1.05, 1.05, 1, 1, 1, 1.05, 1, 1, 1, 1, 0.95, 0.9};
          // Back to school in February and March.
          case OTROS -> new double[] {0.9, 1.15, 1.2, 1, 1, 1, 1, 1, 1, 1, 1, 0.95};
        };
    return table[month - 1];
  }

  /** Two three-day promotions a year per product. */
  private Set<LocalDate> promotions() {
    Set<LocalDate> days = new HashSet<>();
    LocalDate start = today.minusDays(HISTORY_DAYS);
    for (int year = start.getYear(); year <= today.getYear(); year++) {
      for (int k = 0; k < 2; k++) {
        LocalDate first = LocalDate.ofYearDay(year, 10 + random.nextInt(340));
        for (int i = 0; i < 3; i++) {
          days.add(first.plusDays(i));
        }
      }
    }
    return days;
  }

  private LocalDate dateOf(int daysAgo) {
    return today.minusDays(daysAgo);
  }

  private Instant at(int daysAgo, int hour, int minute) {
    return dateOf(daysAgo).atStartOfDay(ZONE).plusHours(hour).plusMinutes(minute).toInstant();
  }

  private int daysAgo(Instant instant) {
    return (int) ChronoUnit.DAYS.between(LocalDate.ofInstant(instant, ZONE), today);
  }

  // ── Stock in ─────────────────────────────────────────────────────────────────

  private void openingStock() {
    for (int i = 0; i < products.size(); i++) {
      int qty = i == 0 ? NEVER_ORDERED_STOCK : Math.min(OPENING_STOCK, capacity[i]);
      receive(i, null, qty, at(HISTORY_DAYS + 1, 7, 0));
    }
  }

  private void deliver(int d) {
    for (Delivery delivery : deliveriesByDay.getOrDefault(d, List.of())) {
      receive(delivery.order.productIdx, delivery.order, delivery.quantity, at(d, 7, 30));
    }
  }

  private void receive(int productIdx, OpenOrder from, int qty, Instant when) {
    Product p = products.get(productIdx);
    List<Position> hosts = positionsByProduct.get(p.getId());
    int count = receptionsPerProduct.merge(p.getId(), 1, Integer::sum);
    Position host = hosts.get(count % hosts.size());
    Reception r = new Reception();
    r.setId(String.format("rec-%05d", receptions.size() + 1));
    r.setRestockOrderId(from == null ? null : from.order.getId());
    r.setProductId(p.getId());
    r.setQuantityReceived(qty);
    r.setDeliveryUnit(host.getSizeStockToSave());
    r.setSupplier(
        from == null ? SUPPLIERS[productIdx % SUPPLIERS.length] : from.order.getSupplier());
    r.setStatus(ReceptionStatus.COMPLETED);
    r.setAssignments(List.of(new Reception.Assignment(host.getId(), qty)));
    r.setReceivedByUserId("u-warehouse");
    r.setCreatedAt(when);
    receptions.add(r);
    stock[productIdx] += qty;
    if (from != null) {
      from.remaining -= qty;
    }
  }

  // ── Stock out ────────────────────────────────────────────────────────────────

  private void processOrders(int d, List<Order> fixed) {
    List<Intent> day = new ArrayList<>();
    if (d >= 1) {
      day.addAll(intents(d));
    }
    fixed.forEach(o -> day.add(new Intent(o.getCreatedAt(), o.getItems(), o)));
    day.sort(Comparator.comparing(Intent::created));
    for (Intent intent : day) {
      if (intent.fixed() != null) {
        applyFixed(intent.fixed(), d);
      } else {
        decide(intent, d);
      }
    }
  }

  private List<Intent> intents(int d) {
    LocalDate date = dateOf(d);
    double growth = 1 + YEARLY_GROWTH * (HISTORY_DAYS - d) / 365.0;
    double expected =
        BASE_ORDERS_PER_DAY
            * WEEKLY[date.getDayOfWeek().getValue() - 1]
            * MONTHLY[date.getMonthValue() - 1]
            * growth
            * spike(date)
            * (0.88 + 0.24 * random.nextDouble());
    int count = (int) Math.round(expected);

    double[] weights = new double[products.size()];
    double total = 0;
    for (int i = 1; i < products.size(); i++) {
      Product p = products.get(i);
      weights[i] =
          popularity[i]
              * categorySeason(p.getCategory(), date.getMonthValue())
              * (promoDays.get(i).contains(date) ? 2.5 : 1.0);
      total += weights[i];
    }

    // Yesterday only up to noon, so even the slowest delivery has completed before now.
    int hours = d == 1 ? 5 : HOUR_WEIGHTS.length;
    List<Intent> result = new ArrayList<>(count);
    for (int k = 0; k < count; k++) {
      Instant created = at(d, FIRST_HOUR + weightedHour(hours), random.nextInt(60));
      result.add(new Intent(created, items(weights, total), null));
    }
    return result;
  }

  private int weightedHour(int limit) {
    int sum = 0;
    for (int h = 0; h < limit; h++) {
      sum += HOUR_WEIGHTS[h];
    }
    int pick = random.nextInt(sum);
    for (int h = 0; h < limit; h++) {
      pick -= HOUR_WEIGHTS[h];
      if (pick < 0) {
        return h;
      }
    }
    return limit - 1;
  }

  private List<OrderItem> items(double[] weights, double total) {
    int count = 1 + random.nextInt(3);
    Set<Integer> chosen = new HashSet<>();
    List<OrderItem> items = new ArrayList<>(count);
    while (items.size() < count) {
      double pick = random.nextDouble() * total;
      int idx = 1;
      for (; idx < weights.length - 1; idx++) {
        pick -= weights[idx];
        if (pick < 0) {
          break;
        }
      }
      if (chosen.add(idx)) {
        Product p = products.get(idx);
        int qty = Math.min(1 + random.nextInt(4), p.getMaxQuantityPerOrder());
        items.add(new OrderItem(p.getId(), p.getSku(), qty));
      }
    }
    return items;
  }

  private void decide(Intent intent, int d) {
    int n = ++orderNumber;
    if (random.nextDouble() < CUSTOMER_CANCEL_RATE) {
      orders.add(
          maker.make(
              n,
              OrderStatus.CANCELLED,
              intent.items(),
              null,
              intent.created(),
              CUSTOMER_REASONS[n % CUSTOMER_REASONS.length]));
      return;
    }
    boolean inStock =
        intent.items().stream()
            .allMatch(i -> available(indexById.get(i.getProductId())) >= i.getQuantity());
    if (!inStock) {
      orders.add(
          maker.make(n, OrderStatus.CANCELLED, intent.items(), null, intent.created(), STOCKOUT));
      return;
    }
    for (OrderItem item : intent.items()) {
      int idx = indexById.get(item.getProductId());
      stock[idx] -= item.getQuantity();
      units[idx][d] += item.getQuantity();
    }
    orders.add(
        maker.make(
            n,
            OrderStatus.COMPLETED,
            intent.items(),
            vehicles.get(n % vehicles.size()),
            intent.created(),
            null));
  }

  private void applyFixed(Order o, int d) {
    if (o.getStatus() == OrderStatus.COMPLETED
        && o.getItems().stream()
            .anyMatch(i -> available(indexById.get(i.getProductId())) < i.getQuantity())) {
      // A shipped order needs the stock to ship; without it, it is a stockout like any other.
      int n = Integer.parseInt(o.getId().substring(2));
      orders.add(
          maker.make(n, OrderStatus.CANCELLED, o.getItems(), null, o.getCreatedAt(), STOCKOUT));
      return;
    }
    orders.add(o);
    for (OrderItem item : o.getItems()) {
      int idx = indexById.get(item.getProductId());
      switch (o.getStatus()) {
        case PENDING, IN_PROGRESS -> {
          reserved[idx] += item.getQuantity();
          units[idx][d] += item.getQuantity();
        }
        case COMPLETED -> {
          stock[idx] -= item.getQuantity();
          units[idx][d] += item.getQuantity();
        }
        default -> {
          // CANCELLED: neither demand nor stock.
        }
      }
    }
  }

  private int available(int idx) {
    return stock[idx] - reserved[idx];
  }

  // ── The buyer ────────────────────────────────────────────────────────────────

  private void review(int d) {
    for (int i = 1; i < products.size(); i++) {
      RestockResult r = formula(i, d, onOrder(i));
      if (!r.shouldRestock()) {
        continue;
      }
      int qty = Math.min(r.suggestedQuantity(), capacity[i] - stock[i] - onOrder(i));
      if (qty >= MIN_PURCHASE) {
        purchase(i, qty, at(d, 18, 0), d);
      }
    }
  }

  /**
   * The restock formula as of day {@code d}. Each window is averaged over the days it actually has
   * history for, so the first weeks of the simulation do not read as near-zero demand.
   */
  private RestockResult formula(int i, int d, int onOrder) {
    return RestockFormula.compute(
        policy,
        average(i, d + policy.recentDays(), d + policy.longDays()),
        average(i, d, d + policy.recentDays()),
        available(i),
        onOrder);
  }

  /** Mean units per day over days-ago {@code [from, to)}, ignoring days before the history. */
  private double average(int i, int from, int to) {
    int sum = 0;
    int days = 0;
    for (int k = from; k < to && k <= HISTORY_DAYS; k++) {
      sum += units[i][k];
      days++;
    }
    return days == 0 ? 0 : (double) sum / days;
  }

  private int onOrder(int i) {
    return open.stream().filter(o -> o.productIdx == i).mapToInt(o -> o.remaining).sum();
  }

  /**
   * Places a purchase order; deliveries landing in the past are scheduled, later ones stay open.
   */
  private OpenOrder purchase(int i, int qty, Instant when, int d) {
    Product p = products.get(i);
    RestockOrder ro = new RestockOrder();
    ro.setId(String.format("ro-%05d", restockOrders.size() + 1));
    ro.setProductId(p.getId());
    ro.setQuantityRequested(qty);
    ro.setSupplier(SUPPLIERS[(i + restockOrders.size()) % SUPPLIERS.length]);
    ro.setRequestedByUserId("u-warehouse");
    ro.setCreatedAt(when);
    restockOrders.add(ro);
    OpenOrder order = new OpenOrder(ro, i);
    open.add(order);

    // Three to six days: around the five the policy assumes, early as often as late.
    int arrival = d - (3 + random.nextInt(4));
    if (qty >= 10 && random.nextDouble() < PARTIAL_DELIVERY_RATE) {
      int first = (int) Math.round(qty * 0.6);
      schedule(arrival, order, first);
      schedule(arrival - (2 + random.nextInt(3)), order, qty - first);
    } else {
      schedule(arrival, order, qty);
    }
    return order;
  }

  private void schedule(int day, OpenOrder order, int qty) {
    if (day >= 1) {
      deliveriesByDay.computeIfAbsent(day, k -> new ArrayList<>()).add(new Delivery(order, qty));
    }
  }

  // ── End state ────────────────────────────────────────────────────────────────

  private void shapeEndState() {
    // Whatever is still in transit lands this morning, so only the orders placed below are open.
    for (OpenOrder o : open) {
      if (o.remaining > 0) {
        receive(o.productIdx, o, o.remaining, now.minus(1, ChronoUnit.HOURS));
      }
    }
    open.clear();

    Map<String, RestockInputs.Demand> before = demand();
    for (int i = 1; i < products.size(); i++) {
      int group = (i - 1) % 3;
      if (group == 2) {
        continue;
      }
      // Restock now / covered by transit: large customer orders over the last days took the
      // product under its reorder point.
      RestockResult r = endFormula(i, before, 0);
      int floor = (int) Math.floor(r.reorderPoint() * 0.8);
      bulkOrder(i, available(i) - floor);
    }

    Map<String, RestockInputs.Demand> after = demand();
    boolean partialSeeded = false;
    for (int i = 1; i < products.size(); i++) {
      int group = (i - 1) % 3;
      RestockResult r = endFormula(i, after, 0);
      int target = (int) Math.ceil(r.targetStock());
      if (group == 1) {
        // Covered: the suggested purchase was placed a day or three ago and is on its way.
        int qty = Math.min(target - available(i), capacity[i] - stock[i]);
        if (qty <= 0) {
          continue;
        }
        OpenOrder order =
            purchase(i, qty, now.minus(1 + i % 3, ChronoUnit.DAYS), 0 /* arrives later */);
        if (!partialSeeded && qty >= 2) {
          receive(i, order, qty / 2, now.minus(12, ChronoUnit.HOURS));
          partialSeeded = true;
        }
      } else if (group == 2 && available(i) <= Math.max(r.reorderPoint(), target - 1)) {
        // Healthy: a top-up delivered this morning.
        int qty =
            Math.min((int) Math.ceil(r.targetStock() * 1.2) - available(i), capacity[i] - stock[i]);
        if (qty > 0) {
          OpenOrder order = purchase(i, qty, now.minus(8, ChronoUnit.DAYS), 0);
          receive(i, order, qty, now.minus(3, ChronoUnit.HOURS));
        }
      }
    }
    placeStock();
  }

  private Map<String, RestockInputs.Demand> demand() {
    return RestockInputs.demandByProduct(orders, now, policy.recentDays(), policy.longDays());
  }

  private RestockResult endFormula(int i, Map<String, RestockInputs.Demand> demand, int onOrder) {
    RestockInputs.Demand d =
        demand.getOrDefault(products.get(i).getId(), new RestockInputs.Demand(0, 0));
    return RestockFormula.compute(policy, d.longTerm(), d.recent(), available(i), onOrder);
  }

  /**
   * Customer orders totalling {@code qty}, each within the per-order cap, spread over the mornings
   * of the last three days so no single day spikes.
   */
  private void bulkOrder(int i, int qty) {
    Product p = products.get(i);
    int minute = 0;
    int k = 0;
    while (qty > 0) {
      int chunk = Math.min(qty, p.getMaxQuantityPerOrder());
      int n = ++orderNumber;
      orders.add(
          maker.make(
              n,
              OrderStatus.COMPLETED,
              List.of(new OrderItem(p.getId(), p.getSku(), chunk)),
              vehicles.get(n % vehicles.size()),
              at(1 + k % 3, 9 + k / 3 % 3, minute),
              null));
      stock[i] -= chunk;
      qty -= chunk;
      minute = (minute + 7) % 60;
      k++;
    }
  }

  /** Physical stock onto the product's positions, oldest first, each up to its capacity. */
  private void placeStock() {
    for (int i = 0; i < products.size(); i++) {
      List<Position> hosts = positionsByProduct.get(products.get(i).getId());
      int left = stock[i];
      for (int h = 0; h < hosts.size(); h++) {
        Position pos = hosts.get(h);
        int put = h == hosts.size() - 1 ? left : Math.min(left, pos.getMaximumCapacity());
        pos.setCurrentStock(put);
        left -= put;
      }
    }
  }
}
