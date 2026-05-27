package com.usal.whbackend.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.repository.ZoneRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    return new DataInitializer(
        userRepository,
        passwordEncoder,
        email,
        password,
        zoneRepository,
        lineRepository,
        positionRepository);
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
  void run_whenNoZonesExist_seedsWarehouseStructure() throws Exception {
    DataInitializer di = initializer("admin@test.com", "pass");
    when(userRepository.existsByRole(UserRole.SUPERADMIN)).thenReturn(true);
    when(zoneRepository.findAll()).thenReturn(List.of());

    Zone savedZone = new Zone();
    savedZone.setId("zone-a-id");
    when(zoneRepository.save(any(Zone.class))).thenReturn(savedZone);

    Line savedLine = new Line();
    savedLine.setId("line-1-id");
    when(lineRepository.save(any(Line.class))).thenReturn(savedLine);

    di.run(null);

    verify(zoneRepository).save(argThat(z -> "A".equals(z.getZoneCode()) && z.isActive()));
    verify(lineRepository).save(argThat(l -> l.getNumberLine() == 1 && l.isActive()));
    verify(positionRepository, org.mockito.Mockito.times(2)).save(any());
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
