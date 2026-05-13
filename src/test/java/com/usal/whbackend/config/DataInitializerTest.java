package com.usal.whbackend.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

  @Mock UserRepository userRepository;
  @Mock PasswordEncoder passwordEncoder;

  @Test
  void run_whenAdminAlreadyExists_doesNotSave() throws Exception {
    DataInitializer initializer =
        new DataInitializer(userRepository, passwordEncoder, "admin@test.com", "pass");
    when(userRepository.existsByRole(UserRole.ADMIN_SYSTEM)).thenReturn(true);

    initializer.run(null);

    verify(userRepository, never()).save(any());
  }

  @Test
  void run_whenNoAdminAndEnvVarsSet_savesAdminUser() throws Exception {
    DataInitializer initializer =
        new DataInitializer(userRepository, passwordEncoder, "admin@test.com", "secret");
    when(userRepository.existsByRole(UserRole.ADMIN_SYSTEM)).thenReturn(false);
    when(passwordEncoder.encode("secret")).thenReturn("$2a$hash");

    initializer.run(null);

    verify(userRepository)
        .save(
            argThat(
                user ->
                    "admin@test.com".equals(user.getEmail())
                        && UserRole.ADMIN_SYSTEM.equals(user.getRole())
                        && user.isActive()
                        && "$2a$hash".equals(user.getPasswordHash())));
  }

  @Test
  void run_whenNoAdminAndEnvVarsMissing_doesNotSave() throws Exception {
    DataInitializer initializer = new DataInitializer(userRepository, passwordEncoder, null, null);
    when(userRepository.existsByRole(UserRole.ADMIN_SYSTEM)).thenReturn(false);

    initializer.run(null);

    verify(userRepository, never()).save(any());
  }
}
