package com.usal.whbackend.config;

import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.repository.ZoneRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final String adminEmail;
  private final String adminPassword;
  private final ZoneRepository zoneRepository;
  private final LineRepository lineRepository;
  private final PositionRepository positionRepository;

  public DataInitializer(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      @Value("${ADMIN_EMAIL:#{null}}") String adminEmail,
      @Value("${ADMIN_PASSWORD:#{null}}") String adminPassword,
      ZoneRepository zoneRepository,
      LineRepository lineRepository,
      PositionRepository positionRepository) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.adminEmail = adminEmail;
    this.adminPassword = adminPassword;
    this.zoneRepository = zoneRepository;
    this.lineRepository = lineRepository;
    this.positionRepository = positionRepository;
  }

  @Override
  public void run(ApplicationArguments args) {
    seedAdminUser();
    seedWarehouse();
  }

  private void seedAdminUser() {
    if (userRepository.existsByRole(UserRole.ADMIN_SYSTEM)) {
      return;
    }
    if (adminEmail == null || adminPassword == null) {
      log.warn(
          "No admin_system user exists and ADMIN_EMAIL/ADMIN_PASSWORD are not set — skipping seed.");
      return;
    }
    User admin = new User();
    admin.setEmail(adminEmail);
    admin.setName("System Admin");
    admin.setRole(UserRole.ADMIN_SYSTEM);
    admin.setPasswordHash(passwordEncoder.encode(adminPassword));
    admin.setActive(true);
    admin.setCreatedAt(Instant.now());
    userRepository.save(admin);
    log.info("Seeded initial admin_system user: {}", adminEmail);
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

  private void doSeedWarehouse() {

    // Zone A
    Zone zoneA = new Zone();
    zoneA.setZoneCode("A");
    zoneA.setMaxAllowedLines(10);
    zoneA.setActive(true);
    zoneA.setCreatedAt(Instant.now());
    zoneA = zoneRepository.save(zoneA);

    // Line 1 in Zone A
    Line lineA1 = new Line();
    lineA1.setIdZone(zoneA.getId());
    lineA1.setNumberLine(1);
    lineA1.setMaxAllowedPositions(20);
    lineA1.setActive(true);
    lineA1.setCreatedAt(Instant.now());
    lineA1 = lineRepository.save(lineA1);

    // Position P01 in Line A1
    Position posA1P1 = new Position();
    posA1P1.setIdLine(lineA1.getId());
    posA1P1.setIdZone(zoneA.getId());
    posA1P1.setPositionName("P01");
    posA1P1.setMaximumCapacity(200);
    posA1P1.setSizeStockToSave(StockSize.MEDIANO);
    posA1P1.setActive(true);
    posA1P1.setCurrentStock(0);
    posA1P1.setCreatedAt(Instant.now());
    positionRepository.save(posA1P1);

    // Position P02 in Line A1
    Position posA1P2 = new Position();
    posA1P2.setIdLine(lineA1.getId());
    posA1P2.setIdZone(zoneA.getId());
    posA1P2.setPositionName("P02");
    posA1P2.setMaximumCapacity(200);
    posA1P2.setSizeStockToSave(StockSize.GRANDE);
    posA1P2.setActive(true);
    posA1P2.setCurrentStock(0);
    posA1P2.setCreatedAt(Instant.now());
    positionRepository.save(posA1P2);

    log.info("Seeded warehouse structure: Zone A, Line 1, Positions P01-P02");
  }
}
