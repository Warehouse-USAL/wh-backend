package com.usal.whbackend.api.user;

import com.usal.whbackend.domain.User;
import com.usal.whbackend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@Tag(name = "Users", description = "User management — requires ADMIN_SYSTEM role")
@SecurityRequirement(name = "bearer-jwt")
public class UserController {

  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @Operation(summary = "List users", description = "Returns all users, optionally filtered by role and active status")
  @ApiResponse(responseCode = "200", description = "User list")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasRole('ADMIN_SYSTEM')")
  @GetMapping
  public ResponseEntity<List<UserResponse>> getUsers(
      @Parameter(description = "Filter by role (e.g. ADMIN_SALES, OPERATOR)")
      @RequestParam(required = false) String role,
      @Parameter(description = "Filter by active status")
      @RequestParam(required = false) Boolean isActive) {
    return ResponseEntity.ok(
        userService.getUsers(role, isActive).stream().map(this::toResponse).toList());
  }

  @Operation(summary = "Get user by ID")
  @ApiResponse(responseCode = "200", description = "User found")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND")
  @PreAuthorize("hasRole('ADMIN_SYSTEM')")
  @GetMapping("/{id}")
  public ResponseEntity<UserResponse> getUser(@PathVariable String id) {
    return ResponseEntity.ok(toResponse(userService.getUser(id)));
  }

  @Operation(summary = "Create user")
  @ApiResponse(responseCode = "201", description = "User created")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_EXISTS")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasRole('ADMIN_SYSTEM')")
  @PostMapping
  public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(toResponse(userService.createUser(request)));
  }

  @Operation(summary = "Update user", description = "Partial update — only provided fields are changed")
  @ApiResponse(responseCode = "200", description = "User updated")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND")
  @PreAuthorize("hasRole('ADMIN_SYSTEM')")
  @PatchMapping("/{id}")
  public ResponseEntity<UserResponse> updateUser(
      @PathVariable String id, @RequestBody UpdateUserRequest request) {
    return ResponseEntity.ok(toResponse(userService.updateUser(id, request)));
  }

  @Operation(summary = "Reset user password")
  @ApiResponse(responseCode = "204", description = "Password reset successfully")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND")
  @PreAuthorize("hasRole('ADMIN_SYSTEM')")
  @PostMapping("/{id}/reset-password")
  public ResponseEntity<Void> resetPassword(
      @PathVariable String id, @Valid @RequestBody ResetPasswordRequest request) {
    userService.resetPassword(id, request.newPassword());
    return ResponseEntity.noContent().build();
  }

  private UserResponse toResponse(User user) {
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getName(),
        user.getRole().name(),
        user.isActive(),
        user.getCreatedAt());
  }
}
