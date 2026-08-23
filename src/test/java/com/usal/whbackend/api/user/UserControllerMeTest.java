package com.usal.whbackend.api.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.Address;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.service.UserService;
import com.usal.whbackend.service.exception.UserNotFoundException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

/**
 * Covers the self-service {@code /users/me} endpoints. They resolve the caller from the {@link
 * org.springframework.security.core.Authentication} argument, which only gets populated when the
 * real {@link SecurityConfig} filter chain is in place — hence a separate slice from {@link
 * UserControllerTest}.
 */
@WebMvcTest(controllers = UserController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class UserControllerMeTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean UserService userService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private User user(Address address) {
    User u = new User();
    u.setId("USR-001");
    u.setEmail("me@test.com");
    u.setName("New Name");
    u.setRole(UserRole.OPERATOR);
    u.setActive(true);
    u.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    u.setAddress(address);
    return u;
  }

  @Test
  @WithMockUser(username = "USR-001")
  void changePassword_valid_returns200() throws Exception {
    mockMvc
        .perform(
            post("/users/me/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\":\"old\",\"new_password\":\"new\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Contraseña actualizada correctamente."));

    verify(userService).changeMyPassword(eq("USR-001"), any(ChangePasswordRequest.class));
  }

  @Test
  @WithMockUser(username = "USR-001")
  void changePassword_wrongCurrentPassword_returns400() throws Exception {
    doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "WRONG_CURRENT_PASSWORD"))
        .when(userService)
        .changeMyPassword(eq("USR-001"), any(ChangePasswordRequest.class));

    mockMvc
        .perform(
            post("/users/me/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\":\"bad\",\"new_password\":\"new\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("WRONG_CURRENT_PASSWORD"));
  }

  @Test
  @WithMockUser(username = "USR-001")
  void changePassword_blankFields_returns400() throws Exception {
    mockMvc
        .perform(
            post("/users/me/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\":\"\",\"new_password\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void changePassword_withoutAuth_returns401() throws Exception {
    mockMvc
        .perform(
            post("/users/me/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\":\"old\",\"new_password\":\"new\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(username = "USR-001")
  void updateMe_nameAndAddress_returns200WithAddress() throws Exception {
    Address address = new Address();
    address.setStreet("Calle 1");
    address.setDepartment("B");
    address.setFloor("3");
    address.setPostalCode("1000");
    when(userService.updateMe(eq("USR-001"), any(UpdateMeRequest.class))).thenReturn(user(address));

    mockMvc
        .perform(
            patch("/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"New Name\",\"address\":{\"street\":\"Calle 1\","
                        + "\"department\":\"B\",\"floor\":\"3\",\"postal_code\":\"1000\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("New Name"))
        .andExpect(jsonPath("$.address.street").value("Calle 1"))
        .andExpect(jsonPath("$.address.department").value("B"))
        .andExpect(jsonPath("$.address.floor").value("3"))
        .andExpect(jsonPath("$.address.postal_code").value("1000"));
  }

  @Test
  @WithMockUser(username = "USR-001")
  void updateMe_withoutAddress_omitsAddressInResponse() throws Exception {
    when(userService.updateMe(eq("USR-001"), any(UpdateMeRequest.class))).thenReturn(user(null));

    mockMvc
        .perform(
            patch("/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New Name\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("New Name"))
        .andExpect(jsonPath("$.address").doesNotExist());
  }

  @Test
  @WithMockUser(username = "USR-001")
  void updateMe_unknownUser_returns404() throws Exception {
    when(userService.updateMe(eq("USR-001"), any(UpdateMeRequest.class)))
        .thenThrow(new UserNotFoundException("USR-001"));

    mockMvc
        .perform(
            patch("/users/me").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
  }

  @Test
  void updateMe_withoutAuth_returns401() throws Exception {
    mockMvc
        .perform(
            patch("/users/me").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
        .andExpect(status().isUnauthorized());
  }
}
