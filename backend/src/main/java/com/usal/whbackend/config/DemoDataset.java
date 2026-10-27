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
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.service.PositionService;
import com.usal.whbackend.service.metrics.restock.RestockParams;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

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
  private static final String CURRENCY = "ARS";

  /**
   * The business document's example params (RFC_Metricas_Calculadas.md §4.4), which are also the
   * {@code restock-cron} defaults. The simulated buyer in {@link DemoHistory} restocks with them
   * and the end state is shaped against them, so the first daily run — or a dashboard calling
   * {@code POST /metrics/restock-suggestions} with these values — sees every case: restock now,
   * covered by stock in transit, and healthy.
   */
  static final RestockParams DEMO_RESTOCK_PARAMS = new RestockParams(0.3, 7, 60, 2, 5, 7);

  /** Default prefix of every seeded position QR text; override with {@code WH_QR_PREFIX}. */
  public static final String DEFAULT_QR_PREFIX = "WH2|";

  // A position never holds more than this many units, however small the product is.
  private static final int POSITION_MAX_CAPACITY = 1_500;
  // Capacity of a position nobody has assigned a product to yet, by container size.
  private static final Map<StockSize, Integer> EMPTY_POSITION_CAPACITY =
      Map.of(StockSize.CAJA, 100, StockSize.MEDIO_PALLET, 300, StockSize.PALLET, 500);

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
  private final Instant now;
  private final String qrPrefix;
  // The catalogue, warehouse and accounts predate the order history that references them.
  private final Instant catalogueEpoch;

  public DemoDataset(UnaryOperator<String> passwordHasher) {
    this(passwordHasher, Instant.now(), DEFAULT_QR_PREFIX);
  }

  /** As above, with the prefix of the position QR texts (see {@link #DEFAULT_QR_PREFIX}). */
  public DemoDataset(UnaryOperator<String> passwordHasher, String qrPrefix) {
    this(passwordHasher, Instant.now(), qrPrefix);
  }

  /** Anchors the whole dataset at {@code now}; tests use it to build the seed on any date. */
  DemoDataset(UnaryOperator<String> passwordHasher, Instant now) {
    this(passwordHasher, now, DEFAULT_QR_PREFIX);
  }

  DemoDataset(UnaryOperator<String> passwordHasher, Instant now, String qrPrefix) {
    this.passwordHasher = passwordHasher;
    this.now = now;
    this.qrPrefix = qrPrefix == null || qrPrefix.isBlank() ? DEFAULT_QR_PREFIX : qrPrefix;
    this.catalogueEpoch = now.minus(DemoHistory.HISTORY_DAYS + 10L, ChronoUnit.DAYS);
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
    List<Order> nearTerm = buildOrders(users, products, vehicles);
    DemoHistory.Result history =
        new DemoHistory(
                now,
                DEMO_RESTOCK_PARAMS,
                products,
                positions,
                vehicles,
                (n, status, items, vehicle, created, reason) ->
                    orderWithItems(n, status, users, items, vehicle, created, reason))
            .simulate(nearTerm, nearTerm.size());
    return new DemoData(
        users,
        products,
        zones,
        lines,
        positions,
        vehicles,
        history.orders(),
        history.restockOrders(),
        history.receptions());
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
    u.setCreatedAt(catalogueEpoch.minus(daysAgo, ChronoUnit.DAYS));
    u.setAddress(montevideoAddress(id.hashCode()));
    // Demo providers are named after the supplier they deliver for (see DemoHistory.SUPPLIERS).
    if (role == UserRole.PROVIDER) {
      u.setSupplier(name);
    }
    return u;
  }

  // ── Products ─────────────────────────────────────────────────────────────────

  // Photos come from Wikimedia Commons (licences in docs/seed-images.md). Special:FilePath is the
  // stable entry point: it redirects to upload.wikimedia.org and ?width= serves a resized copy.
  private static final String COMMONS = "https://commons.wikimedia.org/wiki/Special:FilePath/";
  private static final String IMAGE_WIDTH = "?width=800";

  /**
   * One catalogue entry. Dimensions are the retail box in cm and the weight is the boxed product in
   * kg; the price is in whole pesos (the model stores cents).
   */
  private record Item(
      String sku,
      String name,
      String description,
      double heightCm,
      double widthCm,
      double lengthCm,
      double weightKg,
      long pricePesos,
      int minimumStock,
      int maxPerOrder,
      String imageFile,
      String[][] specs) {}

  // Index 0 is the intentionally low-stock, never-ordered item (a new arrival): DemoHistory and its
  // tests rely on that, and the ids stay p-<CAT>-NNN.
  private static final List<Item> CATALOGUE =
      List.of(
          new Item(
              "TEC-001",
              "Pulsera inteligente Xiaomi Mi Band 6",
              "Pulsera de actividad con pantalla AMOLED de 1,56\", monitor de ritmo cardíaco y SpO2",
              5.0,
              9.0,
              14.0,
              0.10,
              65_000,
              50,
              10,
              "Mi_Band_6_default_face.jpg",
              new String[][] {
                {"Marca", "Xiaomi"},
                {"Modelo", "Mi Band 6"},
                {"Pantalla", "AMOLED 1,56\""},
                {"Resistencia al agua", "5 ATM"},
                {"Garantía", "12 meses"}
              }),
          new Item(
              "TEC-002",
              "Mouse inalámbrico Logitech M185",
              "Mouse óptico inalámbrico de 2,4 GHz con receptor nano USB y pila incluida",
              6.5,
              10.0,
              14.0,
              0.10,
              19_900,
              40,
              30,
              "Logitech_M185_mouse_HS01.jpg",
              new String[][] {
                {"Marca", "Logitech"},
                {"Modelo", "M185"},
                {"Conectividad", "Inalámbrico 2,4 GHz (receptor USB)"},
                {"Resolución", "1000 DPI"},
                {"Garantía", "12 meses"}
              }),
          new Item(
              "TEC-003",
              "Teclado mecánico inalámbrico Keychron K8",
              "Teclado mecánico TKL de 87 teclas, Bluetooth 5.1 y cable USB-C, compatible con Mac y"
                  + " Windows",
              6.0,
              16.0,
              42.0,
              1.10,
              165_000,
              15,
              5,
              "Keychron_K8_Non-Backlight_Wireless_Mechanical_Keyboard_-_52772912798.jpg",
              new String[][] {
                {"Marca", "Keychron"},
                {"Modelo", "K8"},
                {"Formato", "TKL (87 teclas)"},
                {"Conectividad", "Bluetooth 5.1 / USB-C"},
                {"Garantía", "12 meses"}
              }),
          new Item(
              "TEC-004",
              "Teclado USB Logitech K120",
              "Teclado de tamaño completo con teclas silenciosas y cable USB de 1,5 m",
              4.5,
              15.0,
              46.0,
              0.65,
              17_500,
              30,
              20,
              "Logitech_K120_Keyboard.jpg",
              new String[][] {
                {"Marca", "Logitech"},
                {"Modelo", "K120"},
                {"Conectividad", "USB con cable"},
                {"Distribución", "Español Latinoamérica"},
                {"Garantía", "12 meses"}
              }),
          new Item(
              "TEC-005",
              "Monitor ASUS ProArt PB278Q 27\"",
              "Monitor LED de 27 pulgadas, resolución WQHD 2560x1440 y panel PLS de 5 ms",
              20.0,
              45.0,
              72.0,
              7.50,
              450_000,
              8,
              3,
              "ASUS_PB278Q_(PXL_20250914_223256075).jpg",
              new String[][] {
                {"Marca", "ASUS"},
                {"Modelo", "ProArt PB278Q"},
                {"Pantalla", "27\" WQHD 2560x1440"},
                {"Entradas", "HDMI, DVI-D, DisplayPort"},
                {"Garantía", "12 meses"}
              }),
          new Item(
              "TEC-006",
              "Webcam Logitech C922 Pro Stream",
              "Cámara web Full HD 1080p a 30 fps con micrófono estéreo y trípode incluido",
              7.0,
              15.0,
              21.0,
              0.35,
              95_000,
              20,
              10,
              "Webcam_(Logitech_c922).jpg",
              new String[][] {
                {"Marca", "Logitech"},
                {"Modelo", "C922 Pro Stream"},
                {"Resolución", "1080p a 30 fps"},
                {"Conectividad", "USB 2.0"},
                {"Garantía", "12 meses"}
              }),
          new Item(
              "TEC-007",
              "Auriculares Bluetooth Sony WH-CH510",
              "Auriculares inalámbricos on-ear con hasta 35 horas de batería y micrófono integrado",
              8.0,
              17.0,
              20.0,
              0.30,
              62_000,
              20,
              10,
              "Sony_WH-CH510_Bluetooth_Over-Ear_Headphone.jpg",
              new String[][] {
                {"Marca", "Sony"},
                {"Modelo", "WH-CH510"},
                {"Conectividad", "Bluetooth 5.0"},
                {"Autonomía", "35 horas"},
                {"Garantía", "12 meses"}
              }),
          new Item(
              "TEC-008",
              "Auriculares gamer Creative Sound BlasterX H5",
              "Auriculares con cable para gaming, drivers de 50 mm y micrófono desmontable",
              11.0,
              22.0,
              24.0,
              0.45,
              175_000,
              10,
              5,
              "Sound_BlasterX_H5_Gaming_Headset.jpg",
              new String[][] {
                {"Marca", "Creative"},
                {"Modelo", "Sound BlasterX H5"},
                {"Conectividad", "Jack 3,5 mm"},
                {"Drivers", "50 mm"},
                {"Garantía", "12 meses"}
              }),
          new Item(
              "TEC-009",
              "Parlante Bluetooth JBL Go 2",
              "Parlante portátil resistente al agua IPX7 con hasta 5 horas de reproducción",
              9.5,
              12.0,
              14.0,
              0.20,
              42_000,
              30,
              15,
              "JBL_GO2_Bluetooth_speaker_00_10_21_131000.jpeg",
              new String[][] {
                {"Marca", "JBL"},
                {"Modelo", "Go 2"},
                {"Conectividad", "Bluetooth 4.1"},
                {"Resistencia al agua", "IPX7"},
                {"Garantía", "12 meses"}
              }),
          new Item(
              "TEC-010",
              "Raspberry Pi 4 Model B 4 GB",
              "Mini computadora con procesador Quad-core Cortex-A72, 4 GB de RAM, Wi-Fi y"
                  + " Bluetooth",
              4.5,
              11.0,
              15.0,
              0.12,
              120_000,
              15,
              5,
              "Raspberry_Pi_4_Model_B_-_Top.jpg",
              new String[][] {
                {"Marca", "Raspberry Pi"},
                {"Modelo", "4 Model B"},
                {"Memoria", "4 GB LPDDR4"},
                {"Conectividad", "Wi-Fi 5, Bluetooth 5.0, Gigabit Ethernet"},
                {"Garantía", "6 meses"}
              }),
          new Item(
              "TEC-011",
              "SSD externo Samsung Portable T5 1 TB",
              "Unidad de estado sólido portátil USB 3.1 con velocidades de lectura de hasta 540 MB/s",
              3.5,
              9.5,
              13.0,
              0.10,
              175_000,
              15,
              5,
              "Samsung_T5_Portable_External_SSD_(Solid_State_Drive)_(43544308485).jpg",
              new String[][] {
                {"Marca", "Samsung"},
                {"Modelo", "Portable SSD T5"},
                {"Capacidad", "1 TB"},
                {"Interfaz", "USB 3.1 Gen 2 (USB-C)"},
                {"Garantía", "36 meses"}
              }),
          new Item(
              "TEC-012",
              "Pendrive Kingston DataTraveler G4 32 GB",
              "Memoria USB 3.0 de 32 GB con capuchón deslizante",
              1.5,
              7.0,
              12.0,
              0.02,
              8_500,
              100,
              50,
              "Kingston_Technology_DataTraveler_G4_USB_flash_drive_USB_3.0_32_Gb.jpg",
              new String[][] {
                {"Marca", "Kingston"},
                {"Modelo", "DataTraveler G4"},
                {"Capacidad", "32 GB"},
                {"Interfaz", "USB 3.0"},
                {"Garantía", "60 meses"}
              }),
          new Item(
              "TEC-013",
              "Tarjeta microSD SanDisk Ultra 32 GB",
              "Tarjeta microSDHC clase 10 de hasta 98 MB/s con adaptador SD",
              1.0,
              6.0,
              10.0,
              0.01,
              7_800,
              100,
              50,
              "SanDisk_Ultra_32GB_MicroSD_card_with_Adapter_-_2015.jpg",
              new String[][] {
                {"Marca", "SanDisk"},
                {"Modelo", "Ultra microSDHC"},
                {"Capacidad", "32 GB"},
                {"Clase", "Clase 10 / UHS-I"},
                {"Garantía", "120 meses"}
              }),
          new Item(
              "TEC-014",
              "Router Wi-Fi TP-Link TL-WR740N",
              "Router inalámbrico N de 150 Mbps con 4 puertos LAN de 100 Mbps y antena fija",
              6.0,
              17.0,
              23.0,
              0.45,
              29_000,
              20,
              10,
              "TP-Link_TL-WR740N_router_HS1.jpg",
              new String[][] {
                {"Marca", "TP-Link"},
                {"Modelo", "TL-WR740N"},
                {"Wi-Fi", "802.11n 150 Mbps"},
                {"Puertos", "4 LAN + 1 WAN"},
                {"Garantía", "12 meses"}
              }),
          new Item(
              "OTR-001",
              "Power bank Xiaomi Mi 10000 mAh",
              "Batería externa de 10.000 mAh con dos salidas USB y carga rápida",
              4.5,
              8.0,
              15.5,
              0.26,
              42_000,
              40,
              20,
              "Xiaomi_10000_mAh_Power_Bank.jpg",
              new String[][] {
                {"Marca", "Xiaomi"},
                {"Capacidad", "10.000 mAh"},
                {"Salidas", "2 x USB-A"},
                {"Entrada", "Micro USB / USB-C"},
                {"Garantía", "6 meses"}
              }),
          new Item(
              "OTR-002",
              "Cable HDMI 2.0 de 1,8 m",
              "Cable HDMI de alta velocidad compatible con 4K a 60 Hz y conectores dorados",
              4.0,
              10.0,
              15.0,
              0.15,
              9_500,
              60,
              30,
              "Computer_HDMI_cable_(2026).jpg",
              new String[][] {
                {"Versión", "HDMI 2.0"},
                {"Longitud", "1,8 m"},
                {"Resolución máxima", "4K a 60 Hz"},
                {"Garantía", "6 meses"}
              }),
          new Item(
              "OTR-003",
              "Cable de red Cat 8 de 2 m",
              "Patch cord blindado Cat 8 con conectores RJ45, hasta 40 Gbps",
              5.0,
              12.0,
              16.0,
              0.20,
              12_500,
              40,
              20,
              "CAT8_Ethernet_Cable-5839.jpg",
              new String[][] {
                {"Categoría", "Cat 8"},
                {"Longitud", "2 m"},
                {"Velocidad máxima", "40 Gbps"},
                {"Garantía", "6 meses"}
              }),
          new Item(
              "OTR-004",
              "Cable USB-C a USB-C de 1 m",
              "Cable de carga y datos USB 2.0 con conectores USB-C en ambos extremos",
              1.5,
              8.0,
              12.0,
              0.05,
              6_900,
              80,
              40,
              "Black_charging_cable_type_USB-C_plugs_01.jpg",
              new String[][] {
                {"Conectores", "USB-C a USB-C"},
                {"Longitud", "1 m"},
                {"Carga", "Hasta 60 W"},
                {"Garantía", "6 meses"}
              }),
          new Item(
              "OTR-005",
              "Hub USB 2.0 de 4 puertos Gembird",
              "Concentrador USB 2.0 de 4 puertos con cable integrado",
              4.0,
              10.0,
              15.0,
              0.08,
              9_800,
              30,
              15,
              "USB_hub_Gembird.jpg",
              new String[][] {
                {"Marca", "Gembird"},
                {"Puertos", "4 x USB 2.0"},
                {"Velocidad", "480 Mbps"},
                {"Garantía", "6 meses"}
              }),
          new Item(
              "OTR-006",
              "Cargador inalámbrico Qi de 10 W",
              "Base de carga inalámbrica Qi para celulares compatibles, con cable USB-C",
              4.0,
              12.0,
              12.0,
              0.15,
              15_900,
              30,
              15,
              "Wireless_Charger_white_front.jpg",
              new String[][] {
                {"Estándar", "Qi"},
                {"Potencia", "10 W"},
                {"Entrada", "USB-C"},
                {"Garantía", "6 meses"}
              }));

  private List<Product> buildProducts() {
    List<Product> products = new ArrayList<>();
    for (Item item : CATALOGUE) {
      products.add(product(products.size(), item));
    }
    return products;
  }

  private Product product(int globalIdx, Item item) {
    Product p = new Product();
    String prefix = item.sku().substring(0, 3);
    p.setId("p-" + item.sku());
    p.setSku(item.sku());
    p.setName(item.name());
    p.setDescription(item.description());
    p.setCategory(
        (prefix.equals("TEC") ? ProductCategory.TECNOLOGIA : ProductCategory.OTROS).name());
    p.setActive(true);
    p.setCreatedAt(catalogueEpoch.plus(5L * 24 + globalIdx, ChronoUnit.HOURS));

    Product.Price price = new Product.Price();
    price.setAmountCents(item.pricePesos() * 100);
    price.setCurrency(CURRENCY);
    price.setTaxIncluded(true);
    p.setPrice(price);

    Product.ProductImage image = new Product.ProductImage();
    image.setUrl(COMMONS + item.imageFile() + IMAGE_WIDTH);
    image.setAlt(item.name());
    image.setPrimary(true);
    p.setImages(List.of(image));

    List<Product.Spec> specs = new ArrayList<>();
    for (String[] entry : item.specs()) {
      Product.Spec spec = new Product.Spec();
      spec.setLabel(entry[0]);
      spec.setValue(entry[1]);
      specs.add(spec);
    }
    p.setSpecs(specs);

    // volume is derived (height x width x length), so it can never disagree with the dimensions.
    p.setHeight(item.heightCm());
    p.setWidth(item.widthCm());
    p.setLength(item.lengthCm());
    p.setWeight(item.weightKg());
    p.setMinimumStock(item.minimumStock());
    p.setMaxQuantityPerOrder(item.maxPerOrder());
    return p;
  }

  // ── Warehouse (zones → lines → positions) ──────────────────────────────────────

  /** A position's planned content: a product (null = empty) and the container it is stored in. */
  private record Slot(String sku, StockSize size) {}

  private static Slot slot(String sku, StockSize size) {
    return new Slot(sku, size);
  }

  private static final StockSize CAJA = StockSize.CAJA;
  private static final StockSize MEDIO = StockSize.MEDIO_PALLET;
  private static final StockSize PALLET = StockSize.PALLET;

  // Zone B: small boxes. Line 1 peripherals, 2 audio and video, 3 storage, 4 cables. The best
  // sellers (mouse, keyboard, speaker, flash drive, microSD, HDMI and USB-C cables) get two
  // positions each.
  private static final Slot[][] ZONE_B = {
    {
      slot("TEC-002", MEDIO),
      slot("TEC-002", MEDIO),
      slot("TEC-004", MEDIO),
      slot("TEC-004", MEDIO),
      slot("TEC-003", MEDIO)
    },
    {
      slot("TEC-006", MEDIO),
      slot("TEC-007", MEDIO),
      slot("TEC-008", MEDIO),
      slot("TEC-009", MEDIO),
      slot("TEC-009", MEDIO)
    },
    {
      slot("TEC-011", CAJA),
      slot("TEC-012", CAJA),
      slot("TEC-012", CAJA),
      slot("TEC-013", CAJA),
      slot("TEC-013", CAJA)
    },
    {
      slot("OTR-002", MEDIO),
      slot("OTR-002", MEDIO),
      slot("OTR-004", CAJA),
      slot("OTR-004", CAJA),
      slot("OTR-003", MEDIO)
    }
  };

  // Zone C: line 1 monitors (bulky, on pallets, three free pallet positions), line 2 networking and
  // power, line 3 mini PCs and wearables (three free boxes).
  private static final Slot[][] ZONE_C = {
    {
      slot("TEC-005", PALLET),
      slot("TEC-005", PALLET),
      slot(null, PALLET),
      slot(null, PALLET),
      slot(null, PALLET)
    },
    {
      slot("TEC-014", MEDIO),
      slot("OTR-001", MEDIO),
      slot("OTR-001", MEDIO),
      slot("OTR-005", CAJA),
      slot("OTR-006", CAJA)
    },
    {
      slot("TEC-010", CAJA),
      slot("TEC-001", CAJA),
      slot(null, CAJA),
      slot(null, CAJA),
      slot(null, CAJA)
    }
  };

  private void buildWarehouse(
      List<Product> products, List<Zone> zones, List<Line> lines, List<Position> positions) {
    Map<String, Product> bySku = new HashMap<>();
    products.forEach(p -> bySku.put(p.getSku(), p));

    Zone zoneB = zone("z-B", "B");
    Zone zoneC = zone("z-C", "C");
    zones.add(zoneB);
    zones.add(zoneC);

    int globalIdx = 0;
    globalIdx = buildZone(zoneB, ZONE_B, bySku, lines, positions, globalIdx);
    buildZone(zoneC, ZONE_C, bySku, lines, positions, globalIdx);
  }

  private int buildZone(
      Zone zone,
      Slot[][] layout,
      Map<String, Product> bySku,
      List<Line> lines,
      List<Position> positions,
      int globalIdx) {
    int inZone = 0;
    for (int li = 0; li < layout.length; li++) {
      Line ln = line("l-" + zone.getZoneCode() + "-" + (li + 1), zone.getId(), li + 1);
      lines.add(ln);
      for (int pi = 0; pi < layout[li].length; pi++) {
        Slot slot = layout[li][pi];
        String name = String.format("P%02d", pi + 1);
        Product product = slot.sku() == null ? null : bySku.get(slot.sku());
        Position position =
            position(
                "pos-" + ln.getId() + "-" + name,
                ln,
                name,
                catalogueEpoch.plus(24L + globalIdx, ChronoUnit.HOURS),
                product,
                slot.size());
        // WH2|P<zone><n>, n counting the zone's positions in order. Infra resolves an order's stops
        // from these texts, so they must match the codes printed in the warehouse.
        position.setQrCode(qrPrefix + "P" + zone.getZoneCode() + (++inZone));
        positions.add(position);
        globalIdx++;
      }
    }
    return globalIdx;
  }

  private Zone zone(String id, String code) {
    Zone z = new Zone();
    z.setId(id);
    z.setZoneCode(code);
    z.setActive(true);
    z.setMaxAllowedLines(10);
    z.setCreatedAt(catalogueEpoch);
    return z;
  }

  private Line line(String id, String idZone, int number) {
    Line l = new Line();
    l.setId(id);
    l.setIdZone(idZone);
    l.setNumberLine(number);
    l.setActive(true);
    l.setMaxAllowedPositions(20);
    l.setCreatedAt(catalogueEpoch.plus(1, ChronoUnit.HOURS));
    return l;
  }

  private Position position(
      String id, Line line, String name, Instant createdAt, Product product, StockSize size) {
    Position p = new Position();
    p.setId(id);
    p.setIdLine(line.getId());
    p.setIdZone(line.getIdZone());
    p.setPositionName(name);
    p.setActive(true);
    p.setSizeStockToSave(size);
    p.setMaximumCapacity(maximumCapacity(product, size));
    p.setProductId(product == null ? null : product.getId());
    p.setCurrentStock(0);
    p.setCreatedAt(createdAt);
    return p;
  }

  /**
   * The most units of {@code product} a {@code size} container takes by volume — the same rule
   * {@code PositionService.updatePosition} enforces — capped at {@link #POSITION_MAX_CAPACITY}, so
   * no stock the simulation puts away can ever be rejected when the position is edited later.
   */
  static int maximumCapacity(Product product, StockSize size) {
    if (product == null) {
      return EMPTY_POSITION_CAPACITY.get(size);
    }
    int byVolume = PositionService.maxUnitsByVolume(product.getVolume(), size.getVolumeCm3());
    int capacity = Math.min(POSITION_MAX_CAPACITY, byVolume);
    // Guard against floating-point rounding right at the boundary.
    while (capacity > 1 && capacity * product.getVolume() > size.getVolumeCm3()) {
      capacity--;
    }
    return capacity;
  }

  // ── Vehicles ─────────────────────────────────────────────────────────────────

  private List<Vehicle> buildVehicles() {
    List<Vehicle> vehicles = new ArrayList<>();
    vehicles.add(vehicle("veh-agv-01", "AGV-01", VehicleStatus.IDLE, 12.5, 4.0, 96, 3, 45L));
    vehicles.add(vehicle("veh-agv-02", "AGV-02", VehicleStatus.IDLE, 30.0, 8.0, 88, 5, 120L));
    vehicles.add(vehicle("veh-agv-03", "AGV-03", VehicleStatus.BUSY, 18.0, 15.0, 64, 1, 10L));
    vehicles.add(vehicle("veh-agv-04", "AGV-04", VehicleStatus.BUSY, 42.0, 22.0, 57, 1, 200L));
    // OFFLINE and ERROR both null out operationSince in the live system (VehicleTelemetryService
    // clears it on any transition into either state) — neither has an
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
    // During working hours on that day, not at whatever time of day the seed happens to run.
    Instant created =
        LocalDate.ofInstant(now, DemoHistory.ZONE)
            .minusDays(daysAgo)
            .atTime(9 + n % 9, n * 7 % 60)
            .atZone(DemoHistory.ZONE)
            .toInstant();
    return makeOrder(n, status, users, products, vehicle, created, cancelReason);
  }

  private Order makeOrder(
      int n,
      OrderStatus status,
      List<User> users,
      List<Product> products,
      Vehicle vehicle,
      Instant created,
      String cancelReason) {
    return orderWithItems(
        n, status, users, buildItems(n, products), vehicle, created, cancelReason);
  }

  private Order orderWithItems(
      int n,
      OrderStatus status,
      List<User> users,
      List<OrderItem> items,
      Vehicle vehicle,
      Instant created,
      String cancelReason) {
    Order o = new Order();
    String id = String.format("o-%04d", n);
    o.setId(id);
    o.setStatus(status);
    o.setPriority(OrderPriority.values()[Math.floorMod(n, OrderPriority.values().length)]);
    o.setRequestedByUserId(users.get(n % users.size()).getId());
    o.setItems(items);
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
        // Jittered by n rather than a fixed 3h/5h pair, so the history has real cycle-time
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
