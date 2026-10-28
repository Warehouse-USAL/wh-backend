package com.usal.whbackend.config;

import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.repository.ZoneRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class DataInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final String adminEmail;
  private final String adminPassword;
  private final ZoneRepository zoneRepository;
  private final LineRepository lineRepository;
  private final PositionRepository positionRepository;
  private final String qrPrefix;

  public DataInitializer(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      @Value("${ADMIN_EMAIL:#{null}}") String adminEmail,
      @Value("${ADMIN_PASSWORD:#{null}}") String adminPassword,
      ZoneRepository zoneRepository,
      LineRepository lineRepository,
      PositionRepository positionRepository,
      @Value("${WH_QR_PREFIX:" + DemoDataset.DEFAULT_QR_PREFIX + "}") String qrPrefix) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.adminEmail = adminEmail;
    this.adminPassword = adminPassword;
    this.zoneRepository = zoneRepository;
    this.lineRepository = lineRepository;
    this.positionRepository = positionRepository;
    this.qrPrefix = DemoDataset.qrPrefixOrDefault(qrPrefix);
  }

  @Override
  public void run(ApplicationArguments args) {
    seedAdminUser();
    seedWarehouse();
  }

  private void seedAdminUser() {
    if (userRepository.existsByRole(UserRole.SUPERADMIN)) {
      return;
    }
    if (adminEmail == null || adminPassword == null) {
      log.warn(
          "No superadmin user exists and ADMIN_EMAIL/ADMIN_PASSWORD are not set — skipping seed.");
      return;
    }
    User admin = new User();
    admin.setEmail(adminEmail);
    admin.setName("System Admin");
    admin.setRole(UserRole.SUPERADMIN);
    admin.setPasswordHash(passwordEncoder.encode(adminPassword));
    admin.setActive(true);
    admin.setCreatedAt(Instant.now());
    userRepository.save(admin);
    log.info("Seeded initial superadmin user: {}", adminEmail);
  }

  private void seedWarehouse() {
    if (!zoneRepository.findAll().isEmpty()) return;
    try {
      doSeedWarehouse();
    } catch (DuplicateKeyException e) {
      // Another instance seeded concurrently — safe to ignore.
      log.info("Warehouse structure already seeded by another instance — skipping.");
    }
  }

  /**
   * The real warehouse (see {@link WarehouseLayout}): one zone, one line, six empty positions whose
   * QR texts are the codes Infra recognises. {@code SEED_DEMO} later fills these same documents
   * with products and stock.
   */
  private void doSeedWarehouse() {
    Instant now = Instant.now();
    zoneRepository.save(WarehouseLayout.zone(now));
    lineRepository.save(WarehouseLayout.line(now));
    for (WarehouseLayout.Slot slot : WarehouseLayout.SLOTS) {
      positionRepository.save(WarehouseLayout.position(slot, null, qrPrefix, now));
    }
    log.info(
        "Seeded warehouse structure: Zone {}, Line {}, {} positions ({}).",
        WarehouseLayout.ZONE_CODE,
        WarehouseLayout.LINE_NUMBER,
        WarehouseLayout.SLOTS.size(),
        WarehouseLayout.SLOTS.stream().map(WarehouseLayout.Slot::name).toList());
  }
}
