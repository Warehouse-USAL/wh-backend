package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

  @Mock UserRepository userRepository;
  @InjectMocks UserDetailsServiceImpl service;

  @Test
  void loadUserByUsername_existingEmail_returnsUserDetails() {
    User user = new User();
    user.setEmail("admin@test.com");
    user.setPasswordHash("$2a$hash");
    user.setRole(UserRole.ADMIN_SYSTEM);
    when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(user));

    UserDetails details = service.loadUserByUsername("admin@test.com");

    assertThat(details.getUsername()).isEqualTo("admin@test.com");
    assertThat(details.getAuthorities()).hasSize(1);
    assertThat(details.getAuthorities().iterator().next().getAuthority())
        .isEqualTo("ROLE_ADMIN_SYSTEM");
  }

  @Test
  void loadUserByUsername_unknownEmail_throwsUsernameNotFoundException() {
    when(userRepository.findByEmail("missing@test.com")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.loadUserByUsername("missing@test.com"))
        .isInstanceOf(UsernameNotFoundException.class);
  }
}
