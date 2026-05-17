package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.api.user.CreateUserRequest;
import com.usal.whbackend.api.user.UpdateUserRequest;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.service.exception.EmailAlreadyExistsException;
import com.usal.whbackend.service.exception.UserNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock UserRepository userRepository;
  @Mock PasswordEncoder passwordEncoder;
  @InjectMocks UserService userService;

  private User sample(String id) {
    User u = new User();
    u.setId(id);
    u.setEmail("user@test.com");
    u.setName("Test User");
    u.setRole(UserRole.ADMIN_SALES);
    u.setActive(true);
    return u;
  }

  @Test
  void getUsers_noFilters_returnsAll() {
    when(userRepository.findAll()).thenReturn(List.of(sample("1")));
    assertThat(userService.getUsers(null, null)).hasSize(1);
  }

  @Test
  void getUsers_roleFilter_returnsByRole() {
    when(userRepository.findByRole(UserRole.ADMIN_SALES)).thenReturn(List.of(sample("1")));
    assertThat(userService.getUsers("ADMIN_SALES", null)).hasSize(1);
  }

  @Test
  void getUsers_activeFilter_returnsByActive() {
    when(userRepository.findByActive(true)).thenReturn(List.of(sample("1")));
    assertThat(userService.getUsers(null, true)).hasSize(1);
  }

  @Test
  void getUsers_bothFilters_returnsByRoleAndActive() {
    when(userRepository.findByRoleAndActive(UserRole.ADMIN_SALES, true))
        .thenReturn(List.of(sample("1")));
    assertThat(userService.getUsers("ADMIN_SALES", true)).hasSize(1);
  }

  @Test
  void getUser_existingId_returnsUser() {
    when(userRepository.findById("1")).thenReturn(Optional.of(sample("1")));
    assertThat(userService.getUser("1").getId()).isEqualTo("1");
  }

  @Test
  void getUser_unknownId_throwsUserNotFound() {
    when(userRepository.findById("999")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> userService.getUser("999")).isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void createUser_newEmail_savesUser() {
    when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
    when(passwordEncoder.encode("pass")).thenReturn("$2a$hash");
    User saved = sample("2");
    saved.setEmail("new@test.com");
    when(userRepository.save(any())).thenReturn(saved);

    User result =
        userService.createUser(
            new CreateUserRequest("new@test.com", "New User", "ADMIN_SALES", "pass"));

    assertThat(result.getEmail()).isEqualTo("new@test.com");
    verify(passwordEncoder).encode("pass");
  }

  @Test
  void createUser_duplicateEmail_throwsEmailAlreadyExists() {
    when(userRepository.findByEmail("dup@test.com")).thenReturn(Optional.of(sample("1")));
    assertThatThrownBy(
            () ->
                userService.createUser(
                    new CreateUserRequest("dup@test.com", "Dup", "ADMIN_SALES", "pass")))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void updateUser_existingId_updatesName() {
    User user = sample("1");
    when(userRepository.findById("1")).thenReturn(Optional.of(user));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    User result = userService.updateUser("1", new UpdateUserRequest("New Name", null, null));
    assertThat(result.getName()).isEqualTo("New Name");
  }

  @Test
  void updateUser_unknownId_throwsUserNotFound() {
    when(userRepository.findById("999")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> userService.updateUser("999", new UpdateUserRequest("N", null, null)))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void resetPassword_existingId_savesNewHash() {
    User user = sample("1");
    when(userRepository.findById("1")).thenReturn(Optional.of(user));
    when(passwordEncoder.encode("newpass")).thenReturn("$2a$newhash");
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    userService.resetPassword("1", "newpass");

    verify(userRepository).save(user);
    assertThat(user.getPasswordHash()).isEqualTo("$2a$newhash");
  }

  @Test
  void resetPassword_unknownId_throwsUserNotFound() {
    when(userRepository.findById("999")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> userService.resetPassword("999", "pass"))
        .isInstanceOf(UserNotFoundException.class);
  }
}
