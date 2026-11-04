package com.usal.whbackend.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.repository.ZoneRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

  @Mock UserRepository userRepository;
  @Mock PasswordEncoder passwordEncoder;
  @Mock ZoneRepository zoneRepository;
  @Mock LineRepository lineRepository;
  @Mock PositionRepository positionRepository;

  private DataInitializer initializer(String email, String password) {
    return initializer(email, password, "WH2|");
  }

  private DataInitializer initializer(String email, String password, String qrPrefix) {
    return new DataInitializer(
        userRepository,
        passwordEncoder,
        email,
        password,
        zoneRepository,
        lineRepository,
        positionRepository,
        qrPrefix);
  }

  @Test
  void run_whenAdminAlreadyExists_doesNotSaveUser() throws Exception {
    DataInitializer di = initializer("admin@test.com", "pass");
    when(userRepository.existsByRole(UserRole.SUPERADMIN)).thenReturn(true);
    when(zoneRepository.findAll()).thenReturn(List.of(new Zone()));

    di.run(null);

    verify(userRepository, never()).save(any());
  }

  @Test
  void run_whenNoAdminAndEnvVarsSet_savesAdminUser() throws Exception {
    DataInitializer di = initializer("admin@test.com", "secret");
    when(userRepository.existsByRole(UserRole.SUPERADMIN)).thenReturn(false);
    when(passwordEncoder.encode("secret")).thenReturn("$2a$hash");
    when(zoneRepository.findAll()).thenReturn(List.of(new Zone()));

    di.run(null);

    verify(userRepository)
        .save(
            argThat(
                user ->
                    "admin@test.com".equals(user.getEmail())
                        && UserRole.SUPERADMIN.equals(user.getRole())
                        && user.isActive()
                        && "$2a$hash".equals(user.getPasswordHash())));
  }

  @Test
  void run_whenNoAdminAndEnvVarsMissing_doesNotSaveUser() throws Exception {
    DataInitializer di = initializer(null, null);
    when(userRepository.existsByRole(UserRole.SUPERADMIN)).thenReturn(false);
    when(zoneRepository.findAll()).thenReturn(List.of(new Zone()));

    di.run(null);

    verify(userRepository, never()).save(any());
  }

  @Test
  void run_whenNoZonesExist_seedsTheRealWarehouseStructure() throws Exception {
    DataInitializer di = initializer("admin@test.com", "pass");
    when(userRepository.existsByRole(UserRole.SUPERADMIN)).thenReturn(true);
    when(zoneRepository.findAll()).thenReturn(List.of());

    di.run(null);

    // One zone, one line, six positions; ids are deterministic so SEED_DEMO fills the same docs.
    verify(zoneRepository)
        .save(
            argThat(
                z ->
                    "A".equals(z.getZoneCode())
                        && "z-A".equals(z.getId())
                        && z.isActive()
                        && z.getMaxAllowedLines() == 1));
    verify(lineRepository)
        .save(
            argThat(
                l ->
                    l.getNumberLine() == 1
                        && "z-A".equals(l.getIdZone())
                        && "l-A-1".equals(l.getId())
                        && l.getMaxAllowedPositions() == 6
                        && l.isActive()));
    verify(positionRepository, org.mockito.Mockito.times(6)).save(any());
  }

  private List<Position> seededPositions(String qrPrefix) throws Exception {
    DataInitializer di = initializer("admin@test.com", "pass", qrPrefix);
    when(userRepository.existsByRole(UserRole.SUPERADMIN)).thenReturn(true);
    when(zoneRepository.findAll()).thenReturn(List.of());

    di.run(null);

    ArgumentCaptor<Position> saved = ArgumentCaptor.forClass(Position.class);
    verify(positionRepository, org.mockito.Mockito.times(6)).save(saved.capture());
    return saved.getAllValues();
  }

  private List<String> seededQrCodes(String qrPrefix) throws Exception {
    return seededPositions(qrPrefix).stream().map(Position::getQrCode).toList();
  }

  @Test
  void run_whenNoZonesExist_givesThePositionsTheWh2QrCodesInfraRecognises() throws Exception {
    org.assertj.core.api.Assertions.assertThat(seededQrCodes("WH2|"))
        .containsExactlyInAnyOrder(
            "WH2|PA1", "WH2|PA2", "WH2|PA3", "WH2|PM1", "WH2|PM2", "WH2|PM3");
  }

  @Test
  void run_whenNoZonesExist_seedsSixEmptyActivePositionsNamedLikeTheirQr() throws Exception {
    List<Position> positions = seededPositions("WH2|");
    org.assertj.core.api.Assertions.assertThat(positions)
        .extracting(Position::getPositionName)
        .containsExactlyInAnyOrder("PA1", "PA2", "PA3", "PM1", "PM2", "PM3");
    org.assertj.core.api.Assertions.assertThat(positions)
        .allSatisfy(
            p -> {
              org.assertj.core.api.Assertions.assertThat(p.getProductId()).isNull();
              org.assertj.core.api.Assertions.assertThat(p.getCurrentStock()).isZero();
              org.assertj.core.api.Assertions.assertThat(p.isActive()).isTrue();
              org.assertj.core.api.Assertions.assertThat(p.getMaximumCapacity()).isPositive();
              org.assertj.core.api.Assertions.assertThat(p.getSizeStockToSave()).isNotNull();
              org.assertj.core.api.Assertions.assertThat(p.getIdLine()).isEqualTo("l-A-1");
              org.assertj.core.api.Assertions.assertThat(p.getIdZone()).isEqualTo("z-A");
            });
  }

  @Test
  void run_withCustomQrPrefix_usesIt() throws Exception {
    org.assertj.core.api.Assertions.assertThat(seededQrCodes("ALM1|"))
        .containsExactlyInAnyOrder(
            "ALM1|PA1", "ALM1|PA2", "ALM1|PA3", "ALM1|PM1", "ALM1|PM2", "ALM1|PM3");
  }

  @Test
  void run_withBlankQrPrefix_fallsBackToTheDefault() throws Exception {
    org.assertj.core.api.Assertions.assertThat(seededQrCodes(""))
        .allMatch(qr -> qr.startsWith("WH2|"));
  }

  @Test
  void run_whenZonesAlreadyExist_doesNotSeedWarehouse() throws Exception {
    DataInitializer di = initializer("admin@test.com", "pass");
    when(userRepository.existsByRole(UserRole.SUPERADMIN)).thenReturn(true);
    when(zoneRepository.findAll()).thenReturn(List.of(new Zone()));

    di.run(null);

    verify(zoneRepository, never()).save(any());
    verify(lineRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
  }
}
