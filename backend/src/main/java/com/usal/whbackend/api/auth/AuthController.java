package com.usal.whbackend.api.auth;

import com.usal.whbackend.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "Login and token management")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @Operation(
      summary = "Login",
      description = "Authenticate with email/password and receive a signed JWT token")
  @ApiResponse(responseCode = "200", description = "Login successful — returns JWT and user info")
  @ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST — body is not valid JSON")
  @ApiResponse(responseCode = "401", description = "Invalid credentials")
  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
    return ResponseEntity.ok(authService.login(request));
  }
}
