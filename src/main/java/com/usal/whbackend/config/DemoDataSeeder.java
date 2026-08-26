package com.usal.whbackend.config;

import com.usal.whbackend.domain.User;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.repository.ZoneRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a complete, interconnected "production-looking" demo dataset on startup.
 *
 * <p>Runs after {@link DataInitializer} (the SUPERADMIN + Zone A baseline) and only when {@code
 * SEED_DEMO=true}. Off by default, so it can never fire on a real deployment unless explicitly
 * enabled. Idempotent: if any products already exist it no-ops, so restarts are safe.
 *
 * <p>The dataset is built in memory by the pure {@link DemoDataset} and persisted via the plain
 * Mongo repositories (not the Kafka-publishing {@code OrderRepository}), so seeding fires no
 * events.
 */
@Component
@Order(2)
public class DemoDataSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final UserRepository userRepository;
  private final ProductRepository productRepository;
  private final ZoneRepository zoneRepository;
  private final LineRepository lineRepository;
  private final PositionRepository positionRepository;
  private final VehicleRepository vehicleRepository;
  private final OrderMongoRepository orderMongoRepository;
  private final PasswordEncoder passwordEncoder;
  private final DemoTelemetrySeeder demoTelemetrySeeder;
  private final boolean seedDemo;

  public DemoDataSeeder(
      UserRepository userRepository,
      ProductRepository productRepository,
      ZoneRepository zoneRepository,
      LineRepository lineRepository,
      PositionRepository positionRepository,
      VehicleRepository vehicleRepository,
      OrderMongoRepository orderMongoRepository,
      PasswordEncoder passwordEncoder,
      DemoTelemetrySeeder demoTelemetrySeeder,
      @Value("${SEED_DEMO:false}") boolean seedDemo) {
    this.userRepository = userRepository;
    this.productRepository = productRepository;
    this.zoneRepository = zoneRepository;
    this.lineRepository = lineRepository;
    this.positionRepository = positionRepository;
    this.vehicleRepository = vehicleRepository;
    this.orderMongoRepository = orderMongoRepository;
    this.passwordEncoder = passwordEncoder;
    this.demoTelemetrySeeder = demoTelemetrySeeder;
    this.seedDemo = seedDemo;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!seedDemo) {
      return;
    }
    if (productRepository.count() > 0) {
      log.info("Demo data already present (products exist) — skipping demo seed.");
      return;
    }

    DemoData data = new DemoDataset(passwordEncoder::encode).build();

    // Persist in dependency order. Ids are already wired in the dataset, so no back-patching.
    userRepository.saveAll(data.getUsers());
    productRepository.saveAll(data.getProducts());
    zoneRepository.saveAll(data.getZones());
    lineRepository.saveAll(data.getLines());
    positionRepository.saveAll(data.getPositions());
    vehicleRepository.saveAll(data.getVehicles());
    orderMongoRepository.saveAll(data.getOrders());

    // Deliberately last and inside the same fresh-database guard: the metrics store has no
    // backfill, so without this the rover charts would be blank next to three weeks of orders.
    demoTelemetrySeeder.seed(data.getVehicles());

    logSeedSummary(data);
  }

  private void logSeedSummary(DemoData data) {
    List<User> users = data.getUsers();
    log.info(
        "Seeded demo data: {} users, {} products, {} zones, {} lines, {} positions, {} vehicles,"
            + " {} orders.",
        users.size(),
        data.getProducts().size(),
        data.getZones().size(),
        data.getLines().size(),
        data.getPositions().size(),
        data.getVehicles().size(),
        data.getOrders().size());
    log.info("Demo accounts (shared password '{}'):", DemoDataset.SHARED_PASSWORD);
    for (User u : users) {
      log.info("  {} — {}", u.getEmail(), u.getRole());
    }
  }
}
