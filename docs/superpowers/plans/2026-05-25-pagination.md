# Pagination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add offset-based pagination (`page`/`size` query params, `pagination` envelope) to all four list endpoints — `/orders`, `/products`, `/users`, `/vehicles` — pushing filtering to MongoDB so counts are always correct.

**Architecture:** `Pagination` record is a shared DTO. `UserRepository` and `VehicleRepository` gain `Pageable`-accepting derived methods (Spring Data generates them). `ProductService` and `OrderRepository` use `MongoTemplate` + `Criteria` for their dynamic multi-condition queries. All four controllers standardise on `{"<resource>": [...], "pagination": {...}}`.

**Tech Stack:** Spring Boot 4.0.6, Spring Data MongoDB, MongoTemplate, Java 21, Gradle, JUnit 5, Mockito, MockMvc

---

## File Map

| Action   | Path |
|----------|------|
| **Create** | `src/main/java/com/usal/whbackend/api/Pagination.java` |
| **Modify** | `src/main/java/com/usal/whbackend/repository/UserRepository.java` |
| **Modify** | `src/main/java/com/usal/whbackend/repository/OrderRepository.java` |
| **Modify** | `src/main/java/com/usal/whbackend/service/UserService.java` |
| **Modify** | `src/main/java/com/usal/whbackend/service/VehicleService.java` |
| **Modify** | `src/main/java/com/usal/whbackend/service/ProductService.java` |
| **Modify** | `src/main/java/com/usal/whbackend/service/OrderService.java` |
| **Modify** | `src/main/java/com/usal/whbackend/api/user/UserController.java` |
| **Modify** | `src/main/java/com/usal/whbackend/api/vehicle/VehicleController.java` |
| **Modify** | `src/main/java/com/usal/whbackend/api/product/ProductController.java` |
| **Modify** | `src/main/java/com/usal/whbackend/api/order/OrderController.java` |
| **Modify** | `src/test/java/com/usal/whbackend/service/UserServiceTest.java` |
| **Modify** | `src/test/java/com/usal/whbackend/service/VehicleServiceTest.java` |
| **Modify** | `src/test/java/com/usal/whbackend/service/ProductServiceTest.java` |
| **Modify** | `src/test/java/com/usal/whbackend/service/OrderServiceTest.java` |
| **Modify** | `src/test/java/com/usal/whbackend/repository/OrderRepositoryTest.java` |
| **Modify** | `src/test/java/com/usal/whbackend/api/user/UserControllerTest.java` |
| **Modify** | `src/test/java/com/usal/whbackend/api/vehicle/VehicleControllerTest.java` |
| **Modify** | `src/test/java/com/usal/whbackend/api/product/ProductControllerTest.java` |
| **Modify** | `src/test/java/com/usal/whbackend/api/order/OrderControllerTest.java` |

---

## Task 1: Create `Pagination` record

**Files:**
- Create: `src/main/java/com/usal/whbackend/api/Pagination.java`

- [ ] **Step 1: Create the record**

```java
package com.usal.whbackend.api;

import org.springframework.data.domain.Page;

public record Pagination(int page, int size, long total_elements, int total_pages) {

  public static Pagination from(Page<?> p) {
    return new Pagination(p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
  }
}
```

- [ ] **Step 2: Confirm existing tests still compile and pass**

```bash
./gradlew test --tests "com.usal.whbackend.*" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` (no change to existing behaviour yet).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/usal/whbackend/api/Pagination.java
git commit -m "feat: add Pagination record"
```

---

## Task 2: Users — repository → service → controller

**Files:**
- Modify: `src/main/java/com/usal/whbackend/repository/UserRepository.java`
- Modify: `src/main/java/com/usal/whbackend/service/UserService.java`
- Modify: `src/test/java/com/usal/whbackend/service/UserServiceTest.java`
- Modify: `src/main/java/com/usal/whbackend/api/user/UserController.java`
- Modify: `src/test/java/com/usal/whbackend/api/user/UserControllerTest.java`

- [ ] **Step 1: Add `Pageable` variants to `UserRepository`**

Replace the full file:

```java
package com.usal.whbackend.repository;

import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRepository extends MongoRepository<User, String> {

  Optional<User> findByEmail(String email);

  List<User> findByRole(UserRole role);

  List<User> findByActive(boolean active);

  List<User> findByRoleAndActive(UserRole role, boolean active);

  boolean existsByRole(UserRole role);

  Page<User> findByRole(UserRole role, Pageable pageable);

  Page<User> findByActive(boolean active, Pageable pageable);

  Page<User> findByRoleAndActive(UserRole role, boolean active, Pageable pageable);
}
```

> `findAll(Pageable)` is already inherited from `MongoRepository` — no declaration needed.

- [ ] **Step 2: Write failing `UserServiceTest`**

Replace the `getUsers_*` tests in `src/test/java/com/usal/whbackend/service/UserServiceTest.java`. Leave all `createUser`, `getUser`, `updateUser`, `resetPassword` tests unchanged.

Full replacement of the `getUsers` test methods (keep the rest of the file):

```java
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
    assertThatThrownBy(() -> userService.getUser("999"))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void createUser_newEmail_savesAndReturns() {
    when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
    when(userRepository.save(any())).thenReturn(sample("USR-NEW"));

    User result =
        userService.createUser(new CreateUserRequest("new@test.com", "Name", "ADMIN_SALES", "pw"));

    assertThat(result.getId()).isEqualTo("USR-NEW");
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
}
```

- [ ] **Step 3: Run to confirm service tests fail**

```bash
./gradlew test --tests "com.usal.whbackend.service.UserServiceTest" 2>&1 | tail -30
```

Expected: FAILED — `getUsers` method not found with new signature.

- [ ] **Step 4: Update `UserService.getUsers` to return `Page<User>`**

Replace the full `UserService.java`:

```java
package com.usal.whbackend.service;

import com.usal.whbackend.api.user.CreateUserRequest;
import com.usal.whbackend.api.user.UpdateUserRequest;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.service.exception.EmailAlreadyExistsException;
import com.usal.whbackend.service.exception.UserNotFoundException;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  public Page<User> getUsers(String role, Boolean active, Pageable pageable) {
    if (role != null && active != null) {
      return userRepository.findByRoleAndActive(UserRole.valueOf(role), active, pageable);
    }
    if (role != null) {
      return userRepository.findByRole(UserRole.valueOf(role), pageable);
    }
    if (active != null) {
      return userRepository.findByActive(active, pageable);
    }
    return userRepository.findAll(pageable);
  }

  public User getUser(String id) {
    return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
  }

  public User createUser(CreateUserRequest request) {
    if (userRepository.findByEmail(request.email()).isPresent()) {
      throw new EmailAlreadyExistsException(request.email());
    }
    User user = new User();
    user.setEmail(request.email());
    user.setName(request.name());
    user.setRole(UserRole.valueOf(request.role()));
    user.setPasswordHash(passwordEncoder.encode(request.initialPassword()));
    user.setActive(true);
    user.setCreatedAt(Instant.now());
    return userRepository.save(user);
  }

  public User updateUser(String id, UpdateUserRequest request) {
    User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    if (request.name() != null) user.setName(request.name());
    if (request.role() != null) user.setRole(UserRole.valueOf(request.role()));
    if (request.active() != null) user.setActive(request.active());
    return userRepository.save(user);
  }

  public void resetPassword(String id, String newPassword) {
    User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    userRepository.save(user);
  }
}
```

- [ ] **Step 5: Run service tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.service.UserServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Write failing `UserControllerTest`**

Replace the full file:

```java
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
```

- [ ] **Step 7: Run to confirm controller tests fail**

```bash
./gradlew test --tests "com.usal.whbackend.api.user.UserControllerTest" 2>&1 | tail -30
```

Expected: FAILED — `getUsers` not found with new signature / response shape mismatch.

- [ ] **Step 8: Update `UserController`**

Replace the full file:

```java
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
      @Parameter(description = "Page size (max 50)") @RequestParam(defaultValue = "10")
          int size) {
    Pageable pageable = PageRequest.of(page, Math.min(size, 50));
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

  @Operation(summary = "Update user", description = "Partial update — only provided fields are changed")
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
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getName(),
        user.getRole().name(),
        user.isActive(),
        user.getCreatedAt());
  }
}
```

- [ ] **Step 9: Run all user tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.api.user.*" --tests "com.usal.whbackend.service.UserServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Apply formatting and commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/usal/whbackend/repository/UserRepository.java \
        src/main/java/com/usal/whbackend/service/UserService.java \
        src/main/java/com/usal/whbackend/api/user/UserController.java \
        src/test/java/com/usal/whbackend/service/UserServiceTest.java \
        src/test/java/com/usal/whbackend/api/user/UserControllerTest.java
git commit -m "feat: paginate GET /users"
```

---

## Task 3: Vehicles — service → controller

> `VehicleRepository.findAll(Pageable)` is already inherited from `MongoRepository` — no changes needed to the repository interface.

**Files:**
- Modify: `src/main/java/com/usal/whbackend/service/VehicleService.java`
- Modify: `src/test/java/com/usal/whbackend/service/VehicleServiceTest.java`
- Modify: `src/main/java/com/usal/whbackend/api/vehicle/VehicleController.java`
- Modify: `src/test/java/com/usal/whbackend/api/vehicle/VehicleControllerTest.java`

- [ ] **Step 1: Write failing `VehicleServiceTest`**

Replace the full file:

```java
package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.usal.whbackend.api.vehicle.RegisterVehicleRequest;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.repository.VehicleRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

  @Mock VehicleRepository vehicleRepository;
  @InjectMocks VehicleService vehicleService;

  @Test
  void getVehicles_returnsPageFromRepository() {
    Pageable pageable = PageRequest.of(0, 10);
    when(vehicleRepository.findAll(pageable))
        .thenReturn(new PageImpl<>(List.of(), pageable, 0));

    Page<Vehicle> result = vehicleService.getVehicles(pageable);

    assertThat(result.getContent()).isEmpty();
    assertThat(result.getTotalElements()).isEqualTo(0);
  }

  @Test
  void getVehicles_delegatesToRepositoryWithCorrectPageable() {
    Pageable pageable = PageRequest.of(1, 5);
    Vehicle v = new Vehicle();
    when(vehicleRepository.findAll(pageable))
        .thenReturn(new PageImpl<>(List.of(v), pageable, 6));

    Page<Vehicle> result = vehicleService.getVehicles(pageable);

    assertThat(result.getNumber()).isEqualTo(1);
    assertThat(result.getSize()).isEqualTo(5);
    assertThat(result.getTotalElements()).isEqualTo(6);
  }

  @Test
  void getVehicle_throwsUnsupported() {
    assertThrows(UnsupportedOperationException.class, () -> vehicleService.getVehicle("id-1"));
  }

  @Test
  void registerVehicle_throwsUnsupported() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> vehicleService.registerVehicle(new RegisterVehicleRequest("Rover-01")));
  }
}
```

- [ ] **Step 2: Run to confirm vehicle service tests fail**

```bash
./gradlew test --tests "com.usal.whbackend.service.VehicleServiceTest" 2>&1 | tail -30
```

Expected: FAILED — `getVehicles(Pageable)` not found.

- [ ] **Step 3: Update `VehicleService`**

Replace the full file:

```java
package com.usal.whbackend.service;

import com.usal.whbackend.api.vehicle.RegisterVehicleRequest;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.repository.VehicleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class VehicleService {

  private final VehicleRepository vehicleRepository;

  public VehicleService(VehicleRepository vehicleRepository) {
    this.vehicleRepository = vehicleRepository;
  }

  public Page<Vehicle> getVehicles(Pageable pageable) {
    return vehicleRepository.findAll(pageable);
  }

  public Vehicle getVehicle(String id) {
    throw new UnsupportedOperationException("not implemented");
  }

  public Vehicle registerVehicle(RegisterVehicleRequest request) {
    throw new UnsupportedOperationException("not implemented");
  }
}
```

- [ ] **Step 4: Run service tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.service.VehicleServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Write failing `VehicleControllerTest`**

Replace the full file:

```java
package com.usal.whbackend.api.vehicle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.service.VehicleService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VehicleController.class)
class VehicleControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean VehicleService vehicleService;
  @MockitoBean JwtService jwtService;

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicles_returns200WithEnvelope() throws Exception {
    Pageable pageable = PageRequest.of(0, 10);
    when(vehicleService.getVehicles(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(), pageable, 0));

    mockMvc
        .perform(get("/vehicles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vehicles").isArray())
        .andExpect(jsonPath("$.pagination.total_elements").value(0))
        .andExpect(jsonPath("$.pagination.page").value(0));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicles_sizeExceedsMax_clampsTo50() throws Exception {
    when(vehicleService.getVehicles(any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(0);
              return new PageImpl<>(List.of(), p, 0);
            });

    mockMvc
        .perform(get("/vehicles").param("size", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.size").value(50));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicle_returns200() throws Exception {
    mockMvc.perform(get("/vehicles/test-id")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void registerVehicle_returns201() throws Exception {
    mockMvc
        .perform(
            post("/vehicles").contentType("application/json").content("{\"name\":\"Rover-01\"}"))
        .andExpect(status().isCreated());
  }
}
```

- [ ] **Step 6: Run to confirm controller tests fail**

```bash
./gradlew test --tests "com.usal.whbackend.api.vehicle.VehicleControllerTest" 2>&1 | tail -30
```

Expected: FAILED — controller calls service without Pageable / wrong response shape.

- [ ] **Step 7: Update `VehicleController`**

Replace the full file:

```java
package com.usal.whbackend.api.vehicle;

import com.usal.whbackend.api.Pagination;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/vehicles")
@Tag(name = "Vehicles", description = "Autonomous vehicle fleet management")
@SecurityRequirement(name = "bearer-jwt")
public class VehicleController {

  private final VehicleService vehicleService;

  public VehicleController(VehicleService vehicleService) {
    this.vehicleService = vehicleService;
  }

  @Operation(summary = "List vehicles", description = "Requires ADMIN_SYSTEM or ADMIN_WAREHOUSE role")
  @ApiResponse(responseCode = "200", description = "Paginated vehicle list")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SYSTEM', 'ADMIN_WAREHOUSE')")
  @GetMapping
  public ResponseEntity<Map<String, Object>> getVehicles(
      @Parameter(description = "Zero-indexed page number") @RequestParam(defaultValue = "0")
          int page,
      @Parameter(description = "Page size (max 50)") @RequestParam(defaultValue = "10") int size) {
    Pageable pageable = PageRequest.of(page, Math.min(size, 50));
    Page<Vehicle> result = vehicleService.getVehicles(pageable);
    return ResponseEntity.ok(
        Map.of(
            "vehicles", result.getContent().stream().map(VehicleResponse::from).toList(),
            "pagination", Pagination.from(result)));
  }

  @Operation(
      summary = "Get vehicle by ID",
      description = "Requires ADMIN_SYSTEM or ADMIN_WAREHOUSE role")
  @ApiResponse(responseCode = "200", description = "Vehicle found")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "VEHICLE_NOT_FOUND")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SYSTEM', 'ADMIN_WAREHOUSE')")
  @GetMapping("/{id}")
  public ResponseEntity<VehicleResponse> getVehicle(@PathVariable String id) {
    return ResponseEntity.ok().build();
  }

  @Operation(
      summary = "Register vehicle",
      description = "Registers a new vehicle in the fleet. Requires ADMIN_SYSTEM role.")
  @ApiResponse(responseCode = "201", description = "Vehicle registered")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SYSTEM')")
  @PostMapping
  public ResponseEntity<VehicleResponse> registerVehicle(
      @RequestBody RegisterVehicleRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }
}
```

- [ ] **Step 8: Run all vehicle tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.api.vehicle.*" --tests "com.usal.whbackend.service.VehicleServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Apply formatting and commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/usal/whbackend/service/VehicleService.java \
        src/main/java/com/usal/whbackend/api/vehicle/VehicleController.java \
        src/test/java/com/usal/whbackend/service/VehicleServiceTest.java \
        src/test/java/com/usal/whbackend/api/vehicle/VehicleControllerTest.java
git commit -m "feat: paginate GET /vehicles"
```

---

## Task 4: Products — MongoTemplate dynamic query → controller

**Files:**
- Modify: `src/main/java/com/usal/whbackend/service/ProductService.java`
- Modify: `src/test/java/com/usal/whbackend/service/ProductServiceTest.java`
- Modify: `src/main/java/com/usal/whbackend/api/product/ProductController.java`
- Modify: `src/test/java/com/usal/whbackend/api/product/ProductControllerTest.java`

- [ ] **Step 1: Write failing `ProductServiceTest` — `getProducts` tests only**

Replace ONLY the `getProducts`-related tests (all methods beginning with `getProducts_`). Leave all `getProduct`, `createProduct`, `updateProduct`, `deleteProduct` tests unchanged. Also add `@Mock MongoTemplate mongoTemplate` to the class.

The updated test class (full file — write this in its entirety):

```java
package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.product.CreateProductRequest;
import com.usal.whbackend.api.product.UpdateProductRequest;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

  @Mock ProductRepository productRepository;
  @Mock MongoTemplate mongoTemplate;
  @InjectMocks ProductService productService;

  private Product activeProduct(String id) {
    Product p = new Product();
    p.setId(id);
    p.setName("Product " + id);
    p.setSku("SKU-" + id);
    p.setCategory("electronics");
    p.setActive(true);
    return p;
  }

  // ── getProducts ────────────────────────────────────────────────────────────

  @Test
  void getProducts_noFilters_returnsActiveProducts() {
    Pageable pageable = PageRequest.of(0, 10);
    Product p = activeProduct("1");
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(p));

    Page<Product> result = productService.getProducts(null, null, null, pageable);

    assertEquals(1, result.getContent().size());
    assertEquals(1L, result.getTotalElements());
    assertTrue(result.getContent().get(0).isActive());
  }

  @Test
  void getProducts_categoryFilter_passesQueryToMongo() {
    Pageable pageable = PageRequest.of(0, 10);
    Product p = activeProduct("1");
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(p));

    Page<Product> result = productService.getProducts("electronics", null, null, pageable);

    assertEquals(1, result.getContent().size());
    verify(mongoTemplate).count(any(Query.class), eq(Product.class));
    verify(mongoTemplate).find(any(Query.class), eq(Product.class));
  }

  @Test
  void getProducts_searchFilter_queriesMongoNotInMemory() {
    Pageable pageable = PageRequest.of(0, 10);
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class)))
        .thenReturn(List.of(activeProduct("1")));

    Page<Product> result = productService.getProducts(null, "widget", null, pageable);

    // MongoDB does the filtering — mongoTemplate.find is called (not in-memory filtering)
    verify(mongoTemplate).find(any(Query.class), eq(Product.class));
    assertEquals(1, result.getContent().size());
  }

  @Test
  void getProducts_inactiveFilter_queriesMongo() {
    Pageable pageable = PageRequest.of(0, 10);
    Product inactive = new Product();
    inactive.setActive(false);
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(inactive));

    Page<Product> result = productService.getProducts(null, null, false, pageable);

    assertEquals(1, result.getContent().size());
  }

  @Test
  void getProducts_secondPage_usesPageableFromArgument() {
    Pageable pageable = PageRequest.of(1, 5);
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(10L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class)))
        .thenReturn(List.of(activeProduct("6"), activeProduct("7")));

    Page<Product> result = productService.getProducts(null, null, null, pageable);

    assertEquals(2, result.getContent().size());
    assertEquals(10L, result.getTotalElements());
    assertEquals(1, result.getNumber());
  }

  // ── getProduct ─────────────────────────────────────────────────────────────

  @Test
  void getProduct_existingActiveProduct_returnsIt() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    assertEquals("1", productService.getProduct("1", null).getId());
  }

  @Test
  void getProduct_inactiveWithNullIsActive_throws404() {
    Product p = new Product();
    p.setActive(false);
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    assertThrows(ResponseStatusException.class, () -> productService.getProduct("1", null));
  }

  @Test
  void getProduct_unknownId_throws404() {
    when(productRepository.findById("none")).thenReturn(Optional.empty());
    assertThrows(ResponseStatusException.class, () -> productService.getProduct("none", null));
  }

  // ── createProduct ──────────────────────────────────────────────────────────

  @Test
  void createProduct_validRequest_savesAndReturns() {
    when(productRepository.findBySku("SKU-001")).thenReturn(Optional.empty());
    Product saved = activeProduct("new");
    when(productRepository.save(any())).thenReturn(saved);

    Product result =
        productService.createProduct(
            new CreateProductRequest(
                "SKU-001", "Widget", "A widget", "tools", null, 10, 5, 2, null, null, null, null));

    assertNotNull(result);
    verify(productRepository).save(any());
  }

  @Test
  void createProduct_missingSku_throws400() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            productService.createProduct(
                new CreateProductRequest(
                    null, "Name", null, "cat", null, null, null, null, null, null, null, null)));
  }

  @Test
  void createProduct_duplicateSku_throws400() {
    when(productRepository.findBySku("SKU-001")).thenReturn(Optional.of(activeProduct("1")));
    assertThrows(
        ResponseStatusException.class,
        () ->
            productService.createProduct(
                new CreateProductRequest(
                    "SKU-001", "Widget", null, "tools", null, 10, 5, 2, null, null, null, null)));
  }

  @Test
  void createProduct_duplicateKeyExceptionFromDb_throws400() {
    when(productRepository.findBySku(any())).thenReturn(Optional.empty());
    when(productRepository.save(any())).thenThrow(new DuplicateKeyException("dup"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            productService.createProduct(
                new CreateProductRequest(
                    "SKU-002", "Widget", null, "tools", null, 10, 5, 2, null, null, null, null)));
  }

  // ── updateProduct ──────────────────────────────────────────────────────────

  @Test
  void updateProduct_existingProduct_updatesFields() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);

    productService.updateProduct("1", new UpdateProductRequest("NewName", null, null, null, null, null, null, null, null, null));

    verify(productRepository).save(any());
  }

  @Test
  void updateProduct_unknownId_throws404() {
    when(productRepository.findById("none")).thenReturn(Optional.empty());
    assertThrows(
        ResponseStatusException.class,
        () ->
            productService.updateProduct(
                "none",
                new UpdateProductRequest(null, null, null, null, null, null, null, null, null, null)));
  }

  // ── deleteProduct ──────────────────────────────────────────────────────────

  @Test
  void deleteProduct_existingProduct_setsActiveFalse() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);

    productService.deleteProduct("1");

    assertFalse(p.isActive());
    verify(productRepository).save(p);
  }

  @Test
  void deleteProduct_unknownId_throws404() {
    when(productRepository.findById("none")).thenReturn(Optional.empty());
    assertThrows(ResponseStatusException.class, () -> productService.deleteProduct("none"));
  }
}
```

> **Note:** `CreateProductRequest` has 12 positional fields: `sku, name, description, category, imageUrl, availableStock, maxQuantityPerOrder, minimumStock, zone, line, position, height`. `UpdateProductRequest` has 10: `name, description, category, imageUrl, availableStock, maxQuantityPerOrder, zone, line, position, height` — no `sku` or `minimumStock`. The constructor calls above match these definitions.

- [ ] **Step 2: Run to confirm product service tests fail**

```bash
./gradlew test --tests "com.usal.whbackend.service.ProductServiceTest" 2>&1 | tail -30
```

Expected: FAILED — `getProducts` wrong signature / MongoTemplate not in constructor.

- [ ] **Step 3: Update `ProductService` to use MongoTemplate**

Replace the full file:

```java
package com.usal.whbackend.service;

import com.usal.whbackend.api.product.CreateProductRequest;
import com.usal.whbackend.api.product.UpdateProductRequest;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.ProductRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {

  private final ProductRepository productRepository;
  private final MongoTemplate mongoTemplate;

  public ProductService(ProductRepository productRepository, MongoTemplate mongoTemplate) {
    this.productRepository = productRepository;
    this.mongoTemplate = mongoTemplate;
  }

  public Page<Product> getProducts(String category, String search, Boolean active, Pageable pageable) {
    Query query = new Query();
    query.addCriteria(Criteria.where("active").is(active != null ? active : true));
    if (category != null) {
      query.addCriteria(Criteria.where("category").is(category));
    }
    if (search != null && !search.isBlank()) {
      query.addCriteria(
          new Criteria()
              .orOperator(
                  Criteria.where("name").regex(search, "i"),
                  Criteria.where("sku").regex(search, "i")));
    }
    long total = mongoTemplate.count(query, Product.class);
    List<Product> items = mongoTemplate.find(query.with(pageable), Product.class);
    return new PageImpl<>(items, pageable, total);
  }

  public Product getProduct(String id, Boolean isActive) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

    if (!Boolean.FALSE.equals(isActive) && !product.isActive()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }

    return product;
  }

  public Product createProduct(CreateProductRequest request) {
    if (request.sku() == null
        || request.sku().isBlank()
        || request.name() == null
        || request.name().isBlank()
        || request.category() == null
        || request.category().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_FIELDS");
    }

    if (productRepository.findBySku(request.sku()).isPresent()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU_ALREADY_EXISTS");
    }

    Product product = new Product();
    product.setSku(request.sku());
    product.setName(request.name());
    product.setDescription(request.description());
    product.setCategory(request.category());
    product.setImageUrl(request.imageUrl());
    product.setAvailableStock(request.availableStock() != null ? request.availableStock() : 0);
    product.setMaxQuantityPerOrder(
        request.maxQuantityPerOrder() != null ? request.maxQuantityPerOrder() : 0);
    product.setMinimumStock(request.minimumStock() != null ? request.minimumStock() : 0);
    product.setZone(request.zone());
    product.setLine(request.line());
    product.setPosition(request.position());
    product.setHeight(request.height());
    product.setActive(true);
    product.setReservedStock(0);
    product.setCreatedAt(Instant.now());

    try {
      return productRepository.save(product);
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU_ALREADY_EXISTS");
    }
  }

  public Product updateProduct(String id, UpdateProductRequest request) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

    if (!product.isActive()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }

    if (request.name() != null) product.setName(request.name());
    if (request.description() != null) product.setDescription(request.description());
    if (request.category() != null) product.setCategory(request.category());
    if (request.imageUrl() != null) product.setImageUrl(request.imageUrl());
    if (request.availableStock() != null) product.setAvailableStock(request.availableStock());
    if (request.maxQuantityPerOrder() != null)
      product.setMaxQuantityPerOrder(request.maxQuantityPerOrder());
    if (request.zone() != null) product.setZone(request.zone());
    if (request.line() != null) product.setLine(request.line());
    if (request.position() != null) product.setPosition(request.position());
    if (request.height() != null) product.setHeight(request.height());

    return productRepository.save(product);
  }

  public void deleteProduct(String id) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

    product.setActive(false);
    productRepository.save(product);
  }
}
```

- [ ] **Step 4: Run product service tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.service.ProductServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Write failing `ProductControllerTest` — update `getProducts_returns200`**

Update only the `getProducts_returns200` test in `src/test/java/com/usal/whbackend/api/product/ProductControllerTest.java`. All other tests are unchanged. Replace just that one method:

```java
@Test
@WithMockUser
void getProducts_returns200() throws Exception {
  Pageable pageable = PageRequest.of(0, 10);
  when(productService.getProducts(any(), any(), any(), any(Pageable.class)))
      .thenReturn(new PageImpl<>(java.util.List.of(sampleProduct), pageable, 1));
  mockMvc
      .perform(get("/products"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.products").isArray())
      .andExpect(jsonPath("$.pagination.total_elements").value(1))
      .andExpect(jsonPath("$.pagination.page").value(0));
}
```

Also add two new tests at the end of the class:

```java
@Test
@WithMockUser
void getProducts_sizeExceedsMax_clampsTo50() throws Exception {
  when(productService.getProducts(any(), any(), any(), any(Pageable.class)))
      .thenAnswer(
          inv -> {
            Pageable p = inv.getArgument(3);
            return new PageImpl<>(java.util.List.of(), p, 0);
          });
  mockMvc
      .perform(get("/products").param("size", "200"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.pagination.size").value(50));
}

@Test
@WithMockUser
void getProducts_explicitPage_passedThrough() throws Exception {
  when(productService.getProducts(any(), any(), any(), any(Pageable.class)))
      .thenAnswer(
          inv -> {
            Pageable p = inv.getArgument(3);
            return new PageImpl<>(java.util.List.of(sampleProduct), p, 25);
          });
  mockMvc
      .perform(get("/products").param("page", "1").param("size", "10"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.pagination.page").value(1))
      .andExpect(jsonPath("$.pagination.total_pages").value(3));
}
```

Also add the missing imports at the top of `ProductControllerTest.java`:

```java
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
```

- [ ] **Step 6: Run to confirm product controller tests fail**

```bash
./gradlew test --tests "com.usal.whbackend.api.product.ProductControllerTest" 2>&1 | tail -30
```

Expected: FAILED — `getProducts` wrong signature / missing `pagination` key.

- [ ] **Step 7: Update `ProductController`**

Replace the full file:

```java
package com.usal.whbackend.api.product;

import com.usal.whbackend.api.Pagination;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
@Tag(name = "Products", description = "Product catalogue and stock management")
@SecurityRequirement(name = "bearer-jwt")
public class ProductController {

  private final ProductService productService;

  public ProductController(ProductService productService) {
    this.productService = productService;
  }

  @Operation(
      summary = "List products",
      description = "Returns paginated products, optionally filtered")
  @ApiResponse(responseCode = "200", description = "Paginated product list")
  @GetMapping
  public ResponseEntity<Map<String, Object>> getProducts(
      @Parameter(description = "Filter by category") @RequestParam(required = false)
          String category,
      @Parameter(description = "Search by name or SKU (case-insensitive)")
          @RequestParam(required = false)
          String search,
      @Parameter(description = "Filter by active status (default: true)")
          @RequestParam(required = false)
          Boolean isActive,
      @Parameter(description = "Zero-indexed page number") @RequestParam(defaultValue = "0")
          int page,
      @Parameter(description = "Page size (max 50)") @RequestParam(defaultValue = "10") int size) {
    Pageable pageable = PageRequest.of(page, Math.min(size, 50));
    Page<Product> result = productService.getProducts(category, search, isActive, pageable);
    return ResponseEntity.ok(
        Map.of(
            "products", result.getContent().stream().map(ProductResponse::from).toList(),
            "pagination", Pagination.from(result)));
  }

  @Operation(summary = "Get product by ID")
  @ApiResponse(responseCode = "200", description = "Product found")
  @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
  @GetMapping("/{id}")
  public ResponseEntity<Map<String, ProductResponse>> getProduct(
      @PathVariable String id,
      @Parameter(description = "Include inactive products when false")
          @RequestParam(required = false)
          Boolean isActive) {
    return ResponseEntity.ok(
        Map.of("product", ProductResponse.from(productService.getProduct(id, isActive))));
  }

  @Operation(summary = "Create product", description = "Requires ADMIN_WAREHOUSE or ADMIN_SALES role")
  @ApiResponse(responseCode = "201", description = "Product created")
  @ApiResponse(responseCode = "400", description = "MISSING_REQUIRED_FIELDS or SKU_ALREADY_EXISTS")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE', 'ADMIN_SALES')")
  @PostMapping
  public ResponseEntity<Map<String, ProductResponse>> createProduct(
      @RequestBody CreateProductRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("product", ProductResponse.from(productService.createProduct(request))));
  }

  @Operation(
      summary = "Update product",
      description =
          "Partial update — only provided fields are changed. Requires ADMIN_WAREHOUSE or ADMIN_SALES role.")
  @ApiResponse(responseCode = "200", description = "Product updated")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE', 'ADMIN_SALES')")
  @PatchMapping("/{id}")
  public ResponseEntity<Map<String, ProductResponse>> updateProduct(
      @PathVariable String id, @RequestBody UpdateProductRequest request) {
    return ResponseEntity.ok(
        Map.of("product", ProductResponse.from(productService.updateProduct(id, request))));
  }

  @Operation(
      summary = "Delete product (soft delete)",
      description = "Sets active=false. Requires ADMIN_WAREHOUSE role.")
  @ApiResponse(responseCode = "204", description = "Product deleted")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteProduct(@PathVariable String id) {
    productService.deleteProduct(id);
    return ResponseEntity.noContent().build();
  }
}
```

- [ ] **Step 8: Run all product tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.api.product.*" --tests "com.usal.whbackend.service.ProductServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Apply formatting and commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/usal/whbackend/service/ProductService.java \
        src/main/java/com/usal/whbackend/api/product/ProductController.java \
        src/test/java/com/usal/whbackend/service/ProductServiceTest.java \
        src/test/java/com/usal/whbackend/api/product/ProductControllerTest.java
git commit -m "feat: paginate GET /products"
```

---

## Task 5: Orders — MongoTemplate in repository → service → controller

**Files:**
- Modify: `src/main/java/com/usal/whbackend/repository/OrderRepository.java`
- Modify: `src/test/java/com/usal/whbackend/repository/OrderRepositoryTest.java`
- Modify: `src/main/java/com/usal/whbackend/service/OrderService.java`
- Modify: `src/test/java/com/usal/whbackend/service/OrderServiceTest.java`
- Modify: `src/main/java/com/usal/whbackend/api/order/OrderController.java`
- Modify: `src/test/java/com/usal/whbackend/api/order/OrderControllerTest.java`

- [ ] **Step 1: Write failing `OrderRepositoryTest` — add `findByFilters` test**

Append the following test to the existing `OrderRepositoryTest` class. Also add `@Mock MongoTemplate mongoTemplate` to the class fields, and update the `setUp` method to use the new three-arg constructor:

```java
// New field — add alongside the existing @Mock fields:
@Mock MongoTemplate mongoTemplate;

// Update setUp to pass mongoTemplate:
@BeforeEach
void setUp() {
  orderRepository = new OrderRepository(mongo, kafka, mongoTemplate);
  @SuppressWarnings("unchecked")
  CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
  when(kafka.send(anyString(), anyString())).thenReturn(future);
}

// New test to append:
@Test
void findByFilters_noFilters_returnsAllOrders() {
  Pageable pageable = PageRequest.of(0, 10);
  Order o1 = new Order();
  o1.setId("ord-1");
  Order o2 = new Order();
  o2.setId("ord-2");
  when(mongoTemplate.count(any(Query.class), eq(Order.class))).thenReturn(2L);
  when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(o1, o2));

  Page<Order> result = orderRepository.findByFilters(null, null, null, null, pageable);

  assertEquals(2, result.getContent().size());
  assertEquals(2L, result.getTotalElements());
}

@Test
void findByFilters_withStatusAndVehicle_queriesMongoTemplate() {
  Pageable pageable = PageRequest.of(0, 10);
  Order o = new Order();
  o.setStatus(OrderStatus.PENDING);
  when(mongoTemplate.count(any(Query.class), eq(Order.class))).thenReturn(1L);
  when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(o));

  Page<Order> result =
      orderRepository.findByFilters(OrderStatus.PENDING, "veh-1", null, null, pageable);

  assertEquals(1, result.getContent().size());
  verify(mongoTemplate).find(any(Query.class), eq(Order.class));
}

@Test
void findByFilters_withFromAndTo_doesNotThrowDuplicateFieldException() {
  // Regression: adding two criteria on "createdAt" (gte + lte) separately would throw
  // InvalidMongoDbApiUsageException. They must be combined into one Criteria chain.
  Pageable pageable = PageRequest.of(0, 10);
  Instant from = Instant.parse("2026-01-01T00:00:00Z");
  Instant to = Instant.parse("2026-12-31T23:59:59Z");
  when(mongoTemplate.count(any(Query.class), eq(Order.class))).thenReturn(0L);
  when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of());

  // Must not throw
  Page<Order> result = orderRepository.findByFilters(null, null, from, to, pageable);

  assertEquals(0, result.getTotalElements());
  verify(mongoTemplate).find(any(Query.class), eq(Order.class));
}
```

Also add these imports to `OrderRepositoryTest.java`:

```java
import com.usal.whbackend.domain.OrderStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
```

- [ ] **Step 2: Run to confirm repository tests fail**

```bash
./gradlew test --tests "com.usal.whbackend.repository.OrderRepositoryTest" 2>&1 | tail -30
```

Expected: FAILED — `OrderRepository` constructor mismatch / `findByFilters` not found.

- [ ] **Step 3: Update `OrderRepository` — add MongoTemplate and `findByFilters`**

Replace the full file:

```java
package com.usal.whbackend.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.repository.kafka.OrderCancelMessage;
import com.usal.whbackend.repository.kafka.OrderDispatchMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderRepository {

  private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);

  private final OrderMongoRepository mongo;
  private final KafkaTemplate<String, String> kafka;
  private final MongoTemplate mongoTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public OrderRepository(
      OrderMongoRepository mongo,
      KafkaTemplate<String, String> kafka,
      MongoTemplate mongoTemplate) {
    this.mongo = mongo;
    this.kafka = kafka;
    this.mongoTemplate = mongoTemplate;
  }

  public Order save(Order order) {
    Order saved = mongo.save(order);
    publishDispatch(saved);
    return saved;
  }

  public Order cancel(Order order, String reason) {
    order.setCancelReason(reason);
    order.setStatus(OrderStatus.CANCELLED);
    Order saved = mongo.save(order);
    publishCancel(saved, reason);
    return saved;
  }

  public Optional<Order> findById(String id) {
    return mongo.findById(id);
  }

  public Page<Order> findByFilters(
      OrderStatus status, String vehicleId, Instant from, Instant to, Pageable pageable) {
    Query query = new Query();
    if (status != null) {
      query.addCriteria(Criteria.where("status").is(status));
    }
    if (vehicleId != null) {
      query.addCriteria(Criteria.where("assignedVehicleId").is(vehicleId));
    }
    // from and to must be combined into a single Criteria for the same field —
    // calling addCriteria() twice on "createdAt" throws InvalidMongoDbApiUsageException.
    if (from != null || to != null) {
      Criteria createdAtCriteria = Criteria.where("createdAt");
      if (from != null) {
        createdAtCriteria = createdAtCriteria.gte(from);
      }
      if (to != null) {
        createdAtCriteria = createdAtCriteria.lte(to);
      }
      query.addCriteria(createdAtCriteria);
    }
    long total = mongoTemplate.count(query, Order.class);
    List<Order> items = mongoTemplate.find(query.with(pageable), Order.class);
    return new PageImpl<>(items, pageable, total);
  }

  public List<Order> findByRequestedByUserId(String userId) {
    return mongo.findByRequestedByUserId(userId);
  }

  private void publishDispatch(Order order) {
    List<OrderDispatchMessage.Item> items =
        order.getItems() == null
            ? List.of()
            : order.getItems().stream()
                .map(
                    i ->
                        new OrderDispatchMessage.Item(
                            i.getProductId(), i.getSku(), i.getQuantity()))
                .toList();

    OrderDispatchMessage msg =
        new OrderDispatchMessage(
            "order.dispatch",
            order.getId(),
            items,
            order.getDestinationArea(),
            Instant.now().toString());
    send("order.dispatch", msg);
  }

  private void publishCancel(Order order, String reason) {
    OrderCancelMessage msg =
        new OrderCancelMessage("order.cancel", order.getId(), reason, Instant.now().toString());
    send("order.cancel", msg);
  }

  private void send(String topic, Object payload) {
    try {
      kafka
          .send(topic, objectMapper.writeValueAsString(payload))
          .whenComplete(
              (result, ex) -> {
                if (ex != null) {
                  log.error("Failed to publish to Kafka topic {}: {}", topic, ex.getMessage());
                }
              });
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize Kafka message for topic " + topic, e);
    }
  }
}
```

> **Note:** The old `findAll()`, `findByStatus()`, and `findByAssignedVehicleId()` methods are removed — replaced by `findByFilters`. `findByRequestedByUserId` is kept as it is used by non-paginated internal logic.

- [ ] **Step 4: Run repository tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.repository.OrderRepositoryTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Write failing `OrderServiceTest` — update `getOrders` tests**

Replace the full `OrderServiceTest.java`:

```java
package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.order.CreateOrderRequest;
import com.usal.whbackend.api.order.CreateOrderRequest.OrderItemRequest;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.ProductRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

  @Mock OrderRepository orderRepository;
  @Mock ProductRepository productRepository;
  @Mock OrderEventPublisher orderEventPublisher;
  @Mock StockEventPublisher stockEventPublisher;
  OrderService orderService;

  @BeforeEach
  void setUp() {
    orderService =
        new OrderService(
            orderRepository,
            productRepository,
            List.of(orderEventPublisher),
            List.of(stockEventPublisher));
  }

  // ── getOrders ──────────────────────────────────────────────────────────────

  @Test
  void getOrders_noFilters_returnsAll() {
    Pageable pageable = PageRequest.of(0, 10);
    Order o1 = new Order();
    Order o2 = new Order();
    when(orderRepository.findByFilters(null, null, null, null, pageable))
        .thenReturn(new PageImpl<>(List.of(o1, o2), pageable, 2));

    Page<Order> result = orderService.getOrders(null, null, null, null, pageable);

    assertEquals(2, result.getContent().size());
  }

  @Test
  void getOrders_statusFilter_parsesAndPassesToRepo() {
    Pageable pageable = PageRequest.of(0, 10);
    Order o1 = new Order();
    o1.setStatus(OrderStatus.PENDING);
    when(orderRepository.findByFilters(eq(OrderStatus.PENDING), isNull(), isNull(), isNull(), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(o1), pageable, 1));

    Page<Order> result = orderService.getOrders("PENDING", null, null, null, pageable);

    assertEquals(1, result.getContent().size());
    assertEquals(OrderStatus.PENDING, result.getContent().get(0).getStatus());
  }

  @Test
  void getOrders_invalidStatus_throws400() {
    Pageable pageable = PageRequest.of(0, 10);
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> orderService.getOrders("INVALIDO", null, null, null, pageable));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_STATUS", ex.getReason());
  }

  @Test
  void getOrders_invalidFromDate_throws400() {
    Pageable pageable = PageRequest.of(0, 10);
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> orderService.getOrders(null, "not-a-date", null, null, pageable));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_DATE_FORMAT", ex.getReason());
  }

  @Test
  void getOrders_invalidToDate_throws400() {
    Pageable pageable = PageRequest.of(0, 10);
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> orderService.getOrders(null, null, "not-a-date", null, pageable));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_DATE_FORMAT", ex.getReason());
  }

  @Test
  void getOrders_validDateRange_parsesInstantsAndPassesToRepo() {
    Pageable pageable = PageRequest.of(0, 10);
    String fromStr = "2026-01-01T00:00:00Z";
    String toStr = "2026-12-31T23:59:59Z";
    Instant fromInstant = Instant.parse(fromStr);
    Instant toInstant = Instant.parse(toStr);
    when(orderRepository.findByFilters(
            isNull(), isNull(), eq(fromInstant), eq(toInstant), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(), pageable, 0));

    Page<Order> result = orderService.getOrders(null, fromStr, toStr, null, pageable);

    assertEquals(0, result.getContent().size());
    verify(orderRepository).findByFilters(null, null, fromInstant, toInstant, pageable);
  }

  // ── getOrder ───────────────────────────────────────────────────────────────

  @Test
  void getOrder_existingId_returnsOrder() {
    Order order = new Order();
    order.setStatus(OrderStatus.PENDING);
    when(orderRepository.findById("id-1")).thenReturn(Optional.of(order));

    Order result = orderService.getOrder("id-1");

    assertEquals(OrderStatus.PENDING, result.getStatus());
  }

  @Test
  void getOrder_unknownId_throws404() {
    when(orderRepository.findById("no-existe")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.getOrder("no-existe"));

    assertEquals(404, ex.getStatusCode().value());
  }

  // ── createOrder ────────────────────────────────────────────────────────────

  @Test
  void createOrder_missingDestination_throws400() {
    CreateOrderRequest req = new CreateOrderRequest(null, List.of());
    assertThrows(
        ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
  }

  @Test
  void createOrder_emptyItems_throws400() {
    CreateOrderRequest req = new CreateOrderRequest("AREA-A", List.of());
    assertThrows(
        ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
  }

  @Test
  void createOrder_validRequest_savesAndPublishes() {
    Product p = new Product();
    p.setId("prod-1");
    p.setSku("SKU-001");
    p.setActive(true);
    p.setAvailableStock(10);
    p.setMaxQuantityPerOrder(5);
    p.setMinimumStock(2);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(p));
    Order saved = new Order();
    saved.setId("ord-new");
    when(orderRepository.save(any())).thenReturn(saved);

    Order result =
        orderService.createOrder(
            new CreateOrderRequest("AREA-A", List.of(new OrderItemRequest("prod-1", 2))),
            "user-1");

    assertEquals("ord-new", result.getId());
    verify(orderRepository).save(any());
    verify(orderEventPublisher).broadcastOrderUpdate(saved);
  }

  @Test
  void createOrder_insufficientStock_throws400() {
    Product p = new Product();
    p.setId("prod-1");
    p.setActive(true);
    p.setAvailableStock(1);
    p.setMaxQuantityPerOrder(5);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(p));

    assertThrows(
        ResponseStatusException.class,
        () ->
            orderService.createOrder(
                new CreateOrderRequest("AREA-A", List.of(new OrderItemRequest("prod-1", 3))),
                "user-1"));
  }

  // ── cancelOrder ────────────────────────────────────────────────────────────

  @Test
  void cancelOrder_pendingOrder_cancelsAndPublishes() {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.PENDING);
    order.setItems(List.of());
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderRepository.cancel(any(), any())).thenReturn(order);

    orderService.cancelOrder("ord-1", "reason");

    verify(orderRepository).cancel(order, "reason");
    verify(orderEventPublisher).broadcastOrderUpdate(order);
  }

  @Test
  void cancelOrder_completedOrder_throws409() {
    Order order = new Order();
    order.setStatus(OrderStatus.COMPLETED);
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.cancelOrder("ord-1", null));

    assertEquals(409, ex.getStatusCode().value());
  }
}
```

- [ ] **Step 6: Run to confirm order service tests fail**

```bash
./gradlew test --tests "com.usal.whbackend.service.OrderServiceTest" 2>&1 | tail -30
```

Expected: FAILED — `getOrders` wrong signature / `findByFilters` not found.

- [ ] **Step 7: Update `OrderService`**

Replace the full file:

```java
package com.usal.whbackend.service;

import com.usal.whbackend.api.order.CreateOrderRequest;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.ProductRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

  private final OrderRepository orderRepository;
  private final ProductRepository productRepository;
  private final List<OrderEventPublisher> orderEventPublishers;
  private final List<StockEventPublisher> stockEventPublishers;

  public OrderService(
      OrderRepository orderRepository,
      ProductRepository productRepository,
      List<OrderEventPublisher> orderEventPublishers,
      List<StockEventPublisher> stockEventPublishers) {
    this.orderRepository = orderRepository;
    this.productRepository = productRepository;
    this.orderEventPublishers = List.copyOf(orderEventPublishers);
    this.stockEventPublishers = List.copyOf(stockEventPublishers);
  }

  public Page<Order> getOrders(
      String status, String from, String to, String vehicleId, Pageable pageable) {
    OrderStatus parsedStatus = null;
    if (status != null) {
      try {
        parsedStatus = OrderStatus.valueOf(status.toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
      }
    }

    Instant fromInstant = null;
    if (from != null) {
      try {
        fromInstant = Instant.parse(from);
      } catch (DateTimeParseException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DATE_FORMAT");
      }
    }

    Instant toInstant = null;
    if (to != null) {
      try {
        toInstant = Instant.parse(to);
      } catch (DateTimeParseException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DATE_FORMAT");
      }
    }

    return orderRepository.findByFilters(parsedStatus, vehicleId, fromInstant, toInstant, pageable);
  }

  public Order getOrder(String id) {
    return orderRepository
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
  }

  public Order createOrder(CreateOrderRequest request, String userId) {
    if (request.destinationArea() == null || request.destinationArea().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DESTINATION_AREA_REQUIRED");
    }
    if (request.items() == null || request.items().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ITEMS_REQUIRED");
    }

    Set<String> seenProductIds = new HashSet<>();
    for (CreateOrderRequest.OrderItemRequest itemRequest : request.items()) {
      if (!seenProductIds.add(itemRequest.productId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DUPLICATE_PRODUCT_IN_ORDER");
      }
    }

    List<OrderItem> items = new ArrayList<>();

    for (CreateOrderRequest.OrderItemRequest itemRequest : request.items()) {
      if (itemRequest.quantity() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
      }

      Product product =
          productRepository
              .findById(itemRequest.productId())
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "PRODUCT_NOT_FOUND"));

      if (!product.isActive()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PRODUCT_INACTIVE");
      }

      if (itemRequest.quantity() > product.getMaxQuantityPerOrder()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUANTITY_EXCEEDS_LIMIT");
      }

      if (product.getAvailableStock() < itemRequest.quantity()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_STOCK");
      }

      productRepository.updateStock(
          product.getId(), -itemRequest.quantity(), itemRequest.quantity());
      productRepository
          .findById(product.getId())
          .ifPresent(
              updated -> {
                if (updated.getAvailableStock() < updated.getMinimumStock()) {
                  stockEventPublishers.forEach(p -> p.broadcastStockAlert(updated));
                }
              });
      items.add(new OrderItem(product.getId(), product.getSku(), itemRequest.quantity()));
    }

    Order order = new Order();
    order.setStatus(OrderStatus.PENDING);
    order.setRequestedByUserId(userId);
    order.setItems(items);
    order.setDestinationArea(request.destinationArea());
    order.setCreatedAt(Instant.now());

    Order saved = orderRepository.save(order);
    orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(saved));
    return saved;
  }

  public Order cancelOrder(String id, String reason) {
    Order order =
        orderRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

    if (order.getStatus() == OrderStatus.COMPLETED || order.getStatus() == OrderStatus.CANCELLED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE");
    }

    if (order.getItems() != null) {
      for (OrderItem item : order.getItems()) {
        productRepository.updateStock(
            item.getProductId(), item.getQuantity(), -item.getQuantity());
      }
    }

    Order cancelled = orderRepository.cancel(order, reason);
    orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(cancelled));
    return cancelled;
  }
}
```

- [ ] **Step 8: Run service tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.service.OrderServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Write failing `OrderControllerTest`**

Replace the full file:

```java
package com.usal.whbackend.api.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.JwtAuthFilter;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.service.OrderService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean OrderService orderService;
  @MockitoBean JwtService jwtService;

  private Order sampleOrder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    sampleOrder = new Order();
    sampleOrder.setStatus(OrderStatus.PENDING);
    sampleOrder.setRequestedByUserId("user-1");
    sampleOrder.setDestinationArea("AREA-A");
    sampleOrder.setItems(List.of());
    sampleOrder.setCreatedAt(Instant.now());
  }

  @Test
  @WithMockUser
  void getOrders_returns200WithEnvelope() throws Exception {
    Pageable pageable = PageRequest.of(0, 10);
    when(orderService.getOrders(any(), any(), any(), any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(sampleOrder), pageable, 1));

    mockMvc
        .perform(get("/orders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders").isArray())
        .andExpect(jsonPath("$.pagination.total_elements").value(1))
        .andExpect(jsonPath("$.pagination.page").value(0))
        .andExpect(jsonPath("$.pagination.size").value(10));
  }

  @Test
  @WithMockUser
  void getOrders_sizeExceedsMax_clampsTo50() throws Exception {
    when(orderService.getOrders(any(), any(), any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(4);
              return new PageImpl<>(List.of(), p, 0);
            });

    mockMvc
        .perform(get("/orders").param("size", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.size").value(50));
  }

  @Test
  @WithMockUser
  void getOrder_returns200() throws Exception {
    when(orderService.getOrder(anyString())).thenReturn(sampleOrder);
    mockMvc
        .perform(get("/orders/test-id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.status").value("pending"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_SALES")
  void createOrder_returns201() throws Exception {
    when(orderService.createOrder(any(), anyString())).thenReturn(sampleOrder);
    mockMvc
        .perform(
            post("/orders")
                .contentType("application/json")
                .content("{\"items\":[],\"destination_area\":\"AREA-A\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.order").exists());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void cancelOrder_returns200() throws Exception {
    when(orderService.cancelOrder(anyString(), any())).thenReturn(sampleOrder);
    mockMvc
        .perform(post("/orders/test-id/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.status").value("pending"));
  }
}
```

- [ ] **Step 10: Run to confirm order controller tests fail**

```bash
./gradlew test --tests "com.usal.whbackend.api.order.OrderControllerTest" 2>&1 | tail -30
```

Expected: FAILED — `getOrders` wrong signature / missing `pagination` key.

- [ ] **Step 11: Update `OrderController`**

Replace the full file:

```java
package com.usal.whbackend.api.order;

import com.usal.whbackend.api.Pagination;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Order lifecycle management")
@SecurityRequirement(name = "bearer-jwt")
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @Operation(
      summary = "List orders",
      description =
          "Returns paginated orders, optionally filtered by status, date range, or assigned vehicle")
  @ApiResponse(responseCode = "200", description = "Paginated order list")
  @ApiResponse(responseCode = "400", description = "INVALID_STATUS or INVALID_DATE_FORMAT")
  @GetMapping
  public ResponseEntity<Map<String, Object>> getOrders(
      @Parameter(description = "Filter by status: pending, in_progress, completed, cancelled")
          @RequestParam(required = false)
          String status,
      @Parameter(description = "ISO-8601 start date (inclusive), e.g. 2026-01-01T00:00:00Z")
          @RequestParam(required = false)
          String from,
      @Parameter(description = "ISO-8601 end date (inclusive), e.g. 2026-12-31T23:59:59Z")
          @RequestParam(required = false)
          String to,
      @Parameter(description = "Filter by assigned vehicle ID") @RequestParam(required = false)
          String vehicleId,
      @Parameter(description = "Zero-indexed page number") @RequestParam(defaultValue = "0")
          int page,
      @Parameter(description = "Page size (max 50)") @RequestParam(defaultValue = "10") int size) {
    Pageable pageable = PageRequest.of(page, Math.min(size, 50));
    Page<Order> result = orderService.getOrders(status, from, to, vehicleId, pageable);
    return ResponseEntity.ok(
        Map.of(
            "orders", result.getContent().stream().map(OrderResponse::from).toList(),
            "pagination", Pagination.from(result)));
  }

  @Operation(summary = "Get order by ID")
  @ApiResponse(responseCode = "200", description = "Order found")
  @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
  @GetMapping("/{id}")
  public ResponseEntity<Map<String, OrderResponse>> getOrder(@PathVariable String id) {
    return ResponseEntity.ok(Map.of("order", OrderResponse.from(orderService.getOrder(id))));
  }

  @Operation(
      summary = "Create order",
      description =
          "Creates a new order and reserves stock. Requires ADMIN_SALES or ADMIN_WAREHOUSE role.")
  @ApiResponse(responseCode = "201", description = "Order created")
  @ApiResponse(
      responseCode = "400",
      description =
          "DESTINATION_AREA_REQUIRED, ITEMS_REQUIRED, PRODUCT_NOT_FOUND, INSUFFICIENT_STOCK, QUANTITY_EXCEEDS_LIMIT, etc.")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SALES', 'ADMIN_WAREHOUSE')")
  @PostMapping
  public ResponseEntity<Map<String, OrderResponse>> createOrder(
      @RequestBody CreateOrderRequest request,
      @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
    String userId = authentication.getName();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("order", OrderResponse.from(orderService.createOrder(request, userId))));
  }

  @Operation(
      summary = "Cancel order",
      description =
          "Cancels an order and restores reserved stock. Requires ADMIN_WAREHOUSE or ADMIN_SALES role.")
  @ApiResponse(responseCode = "200", description = "Order cancelled")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description = "ORDER_NOT_CANCELLABLE — already completed or cancelled")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE', 'ADMIN_SALES')")
  @PostMapping("/{id}/cancel")
  public ResponseEntity<Map<String, OrderResponse>> cancelOrder(
      @PathVariable String id,
      @Parameter(description = "Optional cancellation reason") @RequestParam(required = false)
          String reason) {
    return ResponseEntity.ok(
        Map.of("order", OrderResponse.from(orderService.cancelOrder(id, reason))));
  }
}
```

- [ ] **Step 12: Run all order tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.api.order.*" \
               --tests "com.usal.whbackend.service.OrderServiceTest" \
               --tests "com.usal.whbackend.repository.OrderRepositoryTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 13: Apply formatting and commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/usal/whbackend/repository/OrderRepository.java \
        src/main/java/com/usal/whbackend/service/OrderService.java \
        src/main/java/com/usal/whbackend/api/order/OrderController.java \
        src/test/java/com/usal/whbackend/repository/OrderRepositoryTest.java \
        src/test/java/com/usal/whbackend/service/OrderServiceTest.java \
        src/test/java/com/usal/whbackend/api/order/OrderControllerTest.java
git commit -m "feat: paginate GET /orders"
```

---

## Task 6: Final verification

- [ ] **Step 1: Run the full test suite**

```bash
./gradlew test 2>&1 | tail -40
```

Expected: `BUILD SUCCESSFUL` — all tests pass including ArchUnit and security tests.

- [ ] **Step 2: If ArchUnit fails**

If `ArchitectureTest` reports a violation (e.g. "services should not use MongoTemplate"), read the failing rule and add `MongoTemplate` to the allowed imports for the service layer. The test file is at `src/test/java/com/usal/whbackend/ArchitectureTest.java`.

- [ ] **Step 3: Verify coverage threshold**

```bash
./gradlew jacocoTestReport jacocoTestCoverageVerification 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` — coverage ≥ 80%.

- [ ] **Step 4: Final commit if any fixes were needed**

```bash
./gradlew spotlessApply
git add -A
git commit -m "fix: address post-pagination test and coverage issues"
```
