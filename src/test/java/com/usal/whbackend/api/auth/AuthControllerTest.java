package com.usal.whbackend.api.auth;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.service.AuthService;
import com.usal.whbackend.service.exception.InvalidCredentialsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AuthController.class)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

  @Autowired MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();
  @MockitoBean AuthService authService;
  @MockitoBean JwtService jwtService;

  @Test
  void login_validCredentials_returns200WithTokenAndUser() throws Exception {
    LoginRequest request = new LoginRequest("admin@test.com", "password");
    LoginResponse response =
        new LoginResponse(
            "jwt.token",
            new LoginResponse.UserInfo("USR-001", "Admin", "admin@test.com", "ADMIN_SYSTEM"));
    when(authService.login(request)).thenReturn(response);

    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").value("jwt.token"))
        .andExpect(jsonPath("$.user.email").value("admin@test.com"))
        .andExpect(jsonPath("$.user.role").value("ADMIN_SYSTEM"));
  }

  @Test
  void login_invalidCredentials_returns401WithErrorCode() throws Exception {
    LoginRequest request = new LoginRequest("bad@test.com", "wrong");
    when(authService.login(request)).thenThrow(new InvalidCredentialsException());

    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
  }
}
