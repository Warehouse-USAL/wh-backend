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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

  // ── getUsers ──────────────────────────────────────────────────────────────

  @Test
  void getUsers_noFilters_returnsAll() {
    Pageable pageable = PageRequest.of(0, 10);
    when(userRepository.findAll(pageable))
        .thenReturn(new PageImpl<>(List.of(sample("1")), pageable, 1));

    Page<User> result = userService.getUsers(null, null, pageable);

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getTotalElements()).isEqualTo(1);
  }

  @Test
  void getUsers_roleFilter_returnsByRole() {
    Pageable pageable = PageRequest.of(0, 10);
    when(userRepository.findByRole(UserRole.ADMIN_SALES, pageable))
        .thenReturn(new PageImpl<>(List.of(sample("1")), pageable, 1));

    Page<User> result = userService.getUsers("ADMIN_SALES", null, pageable);

    assertThat(result.getContent()).hasSize(1);
  }

  @Test
  void getUsers_activeFilter_returnsByActive() {
    Pageable pageable = PageRequest.of(0, 10);
    when(userRepository.findByActive(true, pageable))
        .thenReturn(new PageImpl<>(List.of(sample("1")), pageable, 1));

    Page<User> result = userService.getUsers(null, true, pageable);

    assertThat(result.getContent()).hasSize(1);
  }

  @Test
  void getUsers_roleAndActiveFilter_returnsByBoth() {
    Pageable pageable = PageRequest.of(0, 10);
    when(userRepository.findByRoleAndActive(UserRole.ADMIN_SALES, true, pageable))
        .thenReturn(new PageImpl<>(List.of(sample("1")), pageable, 1));

    Page<User> result = userService.getUsers("ADMIN_SALES", true, pageable);

    assertThat(result.getContent()).hasSize(1);
  }

  @Test
  void getUsers_secondPage_passesPageableThrough() {
    Pageable pageable = PageRequest.of(1, 5);
    when(userRepository.findAll(pageable))
        .thenReturn(new PageImpl<>(List.of(sample("6")), pageable, 6));

    Page<User> result = userService.getUsers(null, null, pageable);

    assertThat(result.getNumber()).isEqualTo(1);
    assertThat(result.getSize()).isEqualTo(5);
    assertThat(result.getTotalElements()).isEqualTo(6);
  }

  // ── getUser / createUser / updateUser / resetPassword ─────────────────────

  @Test
  void getUser_existingId_returnsUser() {
    when(userRepository.findById("USR-001")).thenReturn(Optional.of(sample("USR-001")));
    assertThat(userService.getUser("USR-001").getId()).isEqualTo("USR-001");
  }

  @Test
  void getUser_unknownId_throwsUserNotFound() {
    when(userRepository.findById("999")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> userService.getUser("999")).isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void createUser_newEmail_savesAndReturns() {
    when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
    when(userRepository.save(any())).thenReturn(sample("USR-NEW"));
    when(passwordEncoder.encode("pw")).thenReturn("encoded");

    User result =
        userService.createUser(new CreateUserRequest("new@test.com", "Name", "ADMIN_SALES", "pw"));

    assertThat(result.getId()).isEqualTo("USR-NEW");
    verify(passwordEncoder).encode("pw");
    verify(userRepository).save(any());
  }

  @Test
  void createUser_duplicateEmail_throwsEmailAlreadyExists() {
    when(userRepository.findByEmail("dup@test.com")).thenReturn(Optional.of(sample("1")));
    assertThatThrownBy(
            () ->
                userService.createUser(
                    new CreateUserRequest("dup@test.com", "Name", "ADMIN_SALES", "pw")))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void updateUser_existingId_updatesFields() {
    User u = sample("USR-001");
    when(userRepository.findById("USR-001")).thenReturn(Optional.of(u));
    when(userRepository.save(any())).thenReturn(u);

    userService.updateUser("USR-001", new UpdateUserRequest("New Name", null, null));

    verify(userRepository).save(any());
  }

  @Test
  void resetPassword_existingId_encodesAndSaves() {
    User u = sample("USR-001");
    when(userRepository.findById("USR-001")).thenReturn(Optional.of(u));
    when(passwordEncoder.encode("newpass")).thenReturn("encoded");
    when(userRepository.save(any())).thenReturn(u);

    userService.resetPassword("USR-001", "newpass");

    verify(passwordEncoder).encode("newpass");
    verify(userRepository).save(any());
  }

  @Test
  void updateUser_unknownId_throwsUserNotFound() {
    when(userRepository.findById("999")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> userService.updateUser("999", new UpdateUserRequest(null, null, null)))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void resetPassword_unknownId_throwsUserNotFound() {
    when(userRepository.findById("999")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> userService.resetPassword("999", "newpass"))
        .isInstanceOf(UserNotFoundException.class);
  }
}
