package com.usal.whbackend.config;

import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.UserRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  public DataInitializer(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      @Value("${ADMIN_EMAIL:#{null}}") String adminEmail,
      @Value("${ADMIN_PASSWORD:#{null}}") String adminPassword) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.adminEmail = adminEmail;
    this.adminPassword = adminPassword;
  }

  @Override
  public void run(ApplicationArguments args) {
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
}
