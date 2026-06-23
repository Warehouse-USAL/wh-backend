package com.usal.whbackend.api.user;

import com.usal.whbackend.api.Pagination;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

  @Operation(
      summary = "List users",
      description = "Returns paginated users, optionally filtered by role and active status")
  @ApiResponse(responseCode = "200", description = "Paginated user list")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SYSTEM')")
  @GetMapping
  public ResponseEntity<Map<String, Object>> getUsers(
      @Parameter(description = "Filter by role (e.g. ADMIN_SALES, OPERATOR)")
          @RequestParam(required = false)
          String role,
      @Parameter(description = "Filter by active status") @RequestParam(required = false)
          Boolean isActive,
      @Parameter(description = "Zero-indexed page number") @RequestParam(defaultValue = "0")
          int page,
      @Parameter(description = "Page size (max 50)") @RequestParam(defaultValue = "10") int size) {
    Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(Math.min(size, 50), 1));
    Page<User> result = userService.getUsers(role, isActive, pageable);
    return ResponseEntity.ok(
        Map.of(
            "users", result.getContent().stream().map(this::toResponse).toList(),
            "pagination", Pagination.from(result)));
  }

  @Operation(summary = "Get user by ID")
  @ApiResponse(responseCode = "200", description = "User found")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SYSTEM')")
  @GetMapping("/{id}")
  public ResponseEntity<UserResponse> getUser(@PathVariable String id) {
    return ResponseEntity.ok(toResponse(userService.getUser(id)));
  }

  @Operation(summary = "Create user")
  @ApiResponse(responseCode = "201", description = "User created")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_EXISTS")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SYSTEM')")
  @PostMapping
  public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(toResponse(userService.createUser(request)));
  }

  @Operation(
      summary = "Update user",
      description = "Partial update — only provided fields are changed")
  @ApiResponse(responseCode = "200", description = "User updated")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SYSTEM')")
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
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SYSTEM')")
  @PostMapping("/{id}/reset-password")
  public ResponseEntity<Void> resetPassword(
      @PathVariable String id, @Valid @RequestBody ResetPasswordRequest request) {
    userService.resetPassword(id, request.newPassword());
    return ResponseEntity.noContent().build();
  }

  private UserResponse toResponse(User user) {
    return UserResponse.from(user);
  }

 @Operation(summary = "Self-service change password")
@ApiResponse(responseCode = "200", description = "Contraseña actualizada correctamente.")
@ApiResponse(responseCode = "400", description = "WRONG_CURRENT_PASSWORD or SAME_PASSWORD")
@ApiResponse(responseCode = "401", description = "Unauthorized")
@PostMapping("/me/change-password")
public ResponseEntity<Map<String, String>> changePassword(
    @Valid @RequestBody ChangePasswordRequest request,
    org.springframework.security.core.Authentication authentication
) {
  userService.changeMyPassword(authentication.getName(), request);
  return ResponseEntity.ok(Map.of("message", "Contraseña actualizada correctamente."));
}

@Operation(summary = "Update own profile (name and/or address)")
@ApiResponse(responseCode = "200", description = "Profile updated")
@ApiResponse(responseCode = "401", description = "Unauthorized")
@PatchMapping("/me")
public ResponseEntity<UserResponse> updateMe(
    @RequestBody UpdateMeRequest request,
    org.springframework.security.core.Authentication authentication) {
  return ResponseEntity.ok(toResponse(userService.updateMe(authentication.getName(), request)));
}
}
