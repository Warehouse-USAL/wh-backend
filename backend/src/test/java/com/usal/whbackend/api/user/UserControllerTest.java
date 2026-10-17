package com.usal.whbackend.api.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.service.UserService;
import com.usal.whbackend.service.exception.EmailAlreadyExistsException;
import com.usal.whbackend.service.exception.UserNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = UserController.class)
@Import(GlobalExceptionHandler.class)
@WithMockUser(roles = "ADMIN_SYSTEM")
class UserControllerTest {

  @Autowired MockMvc mockMvc;
  private final ObjectMapper objectMapper =
      new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
  @MockitoBean UserService userService;
  @MockitoBean JwtService jwtService;

  private User sample() {
    User u = new User();
    u.setId("USR-001");
    u.setEmail("admin@test.com");
    u.setName("Admin User");
    u.setRole(UserRole.ADMIN_SYSTEM);
    u.setActive(true);
    u.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    return u;
  }

  @Test
  void getUsers_returns200WithEnvelope() throws Exception {
    Pageable pageable = PageRequest.of(0, 10);
    when(userService.getUsers(any(), any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(sample()), pageable, 1));

    mockMvc
        .perform(get("/users"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users[0].id").value("USR-001"))
        .andExpect(jsonPath("$.users[0].role").value("ADMIN_SYSTEM"))
        .andExpect(jsonPath("$.pagination.total_elements").value(1))
        .andExpect(jsonPath("$.pagination.page").value(0))
        .andExpect(jsonPath("$.pagination.size").value(10))
        .andExpect(jsonPath("$.pagination.total_pages").value(1));
  }

  @Test
  void getUsers_withRoleFilter_returns200() throws Exception {
    Pageable pageable = PageRequest.of(0, 10);
    when(userService.getUsers(eq("ADMIN_SYSTEM"), any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(sample()), pageable, 1));

    mockMvc
        .perform(get("/users").param("role", "ADMIN_SYSTEM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users[0].role").value("ADMIN_SYSTEM"))
        .andExpect(jsonPath("$.pagination.total_elements").value(1));
  }

  @Test
  void getUsers_sizeExceedsMax_clampsTo50() throws Exception {
    when(userService.getUsers(any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(2);
              return new PageImpl<>(List.of(), p, 0);
            });

    mockMvc
        .perform(get("/users").param("size", "200"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.size").value(50));
  }

  @Test
  void getUsers_explicitPageAndSize_passedThrough() throws Exception {
    when(userService.getUsers(any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(2);
              return new PageImpl<>(List.of(sample()), p, 11);
            });

    mockMvc
        .perform(get("/users").param("page", "1").param("size", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.page").value(1))
        .andExpect(jsonPath("$.pagination.size").value(5))
        .andExpect(jsonPath("$.pagination.total_elements").value(11))
        .andExpect(jsonPath("$.pagination.total_pages").value(3));
  }

  @Test
  void getUsers_negativePage_clampsToZero() throws Exception {
    when(userService.getUsers(any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(2);
              return new PageImpl<>(List.of(), p, 0);
            });

    mockMvc
        .perform(get("/users").param("page", "-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.page").value(0));
  }

  @Test
  void getUsers_zeroSize_clampsToOne() throws Exception {
    when(userService.getUsers(any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(2);
              return new PageImpl<>(List.of(), p, 0);
            });

    mockMvc
        .perform(get("/users").param("size", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.size").value(1));
  }

  @Test
  void getUsers_invalidRole_returns400() throws Exception {
    when(userService.getUsers(any(), any(), any(Pageable.class)))
        .thenThrow(
            new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ROLE"));

    mockMvc.perform(get("/users").param("role", "FAKE_ROLE")).andExpect(status().isBadRequest());
  }

  @Test
  void getUser_existingId_returns200() throws Exception {
    when(userService.getUser("USR-001")).thenReturn(sample());
    mockMvc
        .perform(get("/users/USR-001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("admin@test.com"));
  }

  @Test
  void getUser_unknownId_returns404WithErrorCode() throws Exception {
    when(userService.getUser("999")).thenThrow(new UserNotFoundException("999"));
    mockMvc
        .perform(get("/users/999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
  }

  @Test
  void createUser_validRequest_returns201() throws Exception {
    when(userService.createUser(any())).thenReturn(sample());
    mockMvc
        .perform(
            post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CreateUserRequest("new@test.com", "New User", "ADMIN_SALES", "pass"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("USR-001"));
  }

  @Test
  void updateUser_existingId_returns200() throws Exception {
    when(userService.updateUser(eq("USR-001"), any())).thenReturn(sample());
    mockMvc
        .perform(
            patch("/users/USR-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new UpdateUserRequest("New Name", null, null))))
        .andExpect(status().isOk());
  }

  @Test
  void resetPassword_existingId_returns204() throws Exception {
    mockMvc
        .perform(
            post("/users/USR-001/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ResetPasswordRequest("newpass"))))
        .andExpect(status().isNoContent());
    verify(userService).resetPassword("USR-001", "newpass");
  }

  @Test
  void createUser_duplicateEmail_returns409() throws Exception {
    when(userService.createUser(any())).thenThrow(new EmailAlreadyExistsException("dup@test.com"));
    mockMvc
        .perform(
            post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CreateUserRequest("dup@test.com", "Name", "ADMIN_SALES", "pass"))))
        .andExpect(status().isConflict());
  }
}
