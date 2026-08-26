package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.api.user.ChangePasswordRequest;
import com.usal.whbackend.api.user.CreateUserRequest;
import com.usal.whbackend.api.user.UpdateMeRequest;
import com.usal.whbackend.api.user.UpdateUserRequest;
import com.usal.whbackend.domain.Address;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

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

  @Test
  void getUsers_invalidRole_throwsBadRequest() {
    Pageable pageable = PageRequest.of(0, 10);
    assertThatThrownBy(() -> userService.getUsers("NOT_A_ROLE", null, pageable))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void updateUser_roleAndActive_areApplied() {
    User u = sample("USR-001");
    when(userRepository.findById("USR-001")).thenReturn(Optional.of(u));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    User result =
        userService.updateUser("USR-001", new UpdateUserRequest("Renamed", "OPERATOR", false));

    assertThat(result.getName()).isEqualTo("Renamed");
    assertThat(result.getRole()).isEqualTo(UserRole.OPERATOR);
    assertThat(result.isActive()).isFalse();
  }

  @Test
  void updateUser_noFields_leavesUserUntouched() {
    User u = sample("USR-001");
    when(userRepository.findById("USR-001")).thenReturn(Optional.of(u));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    User result = userService.updateUser("USR-001", new UpdateUserRequest(null, null, null));

    assertThat(result.getName()).isEqualTo("Test User");
    assertThat(result.getRole()).isEqualTo(UserRole.ADMIN_SALES);
    assertThat(result.isActive()).isTrue();
  }

  // ── changeMyPassword ──────────────────────────────────────────────────────

  @Test
  void changeMyPassword_validCurrentPassword_reencodesAndSaves() {
    User u = sample("USR-001");
    u.setPasswordHash("old-hash");
    when(userRepository.findById("USR-001")).thenReturn(Optional.of(u));
    when(passwordEncoder.matches("old", "old-hash")).thenReturn(true);
    when(passwordEncoder.matches("new", "old-hash")).thenReturn(false);
    when(passwordEncoder.encode("new")).thenReturn("new-hash");

    userService.changeMyPassword("USR-001", new ChangePasswordRequest("old", "new"));

    assertThat(u.getPasswordHash()).isEqualTo("new-hash");
    verify(userRepository).save(u);
  }

  @Test
  void changeMyPassword_wrongCurrentPassword_throwsBadRequest() {
    User u = sample("USR-001");
    u.setPasswordHash("old-hash");
    when(userRepository.findById("USR-001")).thenReturn(Optional.of(u));
    when(passwordEncoder.matches("wrong", "old-hash")).thenReturn(false);

    ChangePasswordRequest req = new ChangePasswordRequest("wrong", "new");
    assertThatThrownBy(() -> userService.changeMyPassword("USR-001", req))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("WRONG_CURRENT_PASSWORD");
    verify(userRepository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void changeMyPassword_samePassword_throwsBadRequest() {
    User u = sample("USR-001");
    u.setPasswordHash("old-hash");
    when(userRepository.findById("USR-001")).thenReturn(Optional.of(u));
    when(passwordEncoder.matches("old", "old-hash")).thenReturn(true);

    ChangePasswordRequest req = new ChangePasswordRequest("old", "old");
    assertThatThrownBy(() -> userService.changeMyPassword("USR-001", req))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("SAME_PASSWORD");
    verify(userRepository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void changeMyPassword_unknownUser_throwsUserNotFound() {
    when(userRepository.findById("999")).thenReturn(Optional.empty());
    ChangePasswordRequest req = new ChangePasswordRequest("old", "new");
    assertThatThrownBy(() -> userService.changeMyPassword("999", req))
        .isInstanceOf(UserNotFoundException.class);
  }

  // ── updateMe ──────────────────────────────────────────────────────────────

  @Test
  void updateMe_nameAndAddress_areApplied() {
    User u = sample("USR-001");
    when(userRepository.findById("USR-001")).thenReturn(Optional.of(u));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    User result =
        userService.updateMe(
            "USR-001",
            new UpdateMeRequest(
                "New Name", new UpdateMeRequest.AddressRequest("Calle 1", "B", "3", "1000")));

    assertThat(result.getName()).isEqualTo("New Name");
    Address address = result.getAddress();
    assertThat(address).isNotNull();
    assertThat(address.getStreet()).isEqualTo("Calle 1");
    assertThat(address.getDepartment()).isEqualTo("B");
    assertThat(address.getFloor()).isEqualTo("3");
    assertThat(address.getPostalCode()).isEqualTo("1000");
  }

  @Test
  void updateMe_onlyName_leavesAddressNull() {
    User u = sample("USR-001");
    when(userRepository.findById("USR-001")).thenReturn(Optional.of(u));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    User result = userService.updateMe("USR-001", new UpdateMeRequest("Only Name", null));

    assertThat(result.getName()).isEqualTo("Only Name");
    assertThat(result.getAddress()).isNull();
  }

  @Test
  void updateMe_emptyRequest_leavesUserUntouched() {
    User u = sample("USR-001");
    when(userRepository.findById("USR-001")).thenReturn(Optional.of(u));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    User result = userService.updateMe("USR-001", new UpdateMeRequest(null, null));

    assertThat(result.getName()).isEqualTo("Test User");
  }

  @Test
  void updateMe_unknownUser_throwsUserNotFound() {
    when(userRepository.findById("999")).thenReturn(Optional.empty());
    UpdateMeRequest req = new UpdateMeRequest("x", null);
    assertThatThrownBy(() -> userService.updateMe("999", req))
        .isInstanceOf(UserNotFoundException.class);
  }
}
