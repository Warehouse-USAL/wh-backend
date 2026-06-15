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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  public Page<User> getUsers(String role, Boolean active, Pageable pageable) {
    UserRole parsedRole = null;
    if (role != null) {
      try {
        parsedRole = UserRole.valueOf(role);
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ROLE");
      }
    }
    if (parsedRole != null && active != null) {
      return userRepository.findByRoleAndActive(parsedRole, active, pageable);
    }
    if (parsedRole != null) {
      return userRepository.findByRole(parsedRole, pageable);
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

  public void changeMyPassword(String userId, com.usal.whbackend.api.user.ChangePasswordRequest request) {
  User user = userRepository.findById(userId)
      .orElseThrow(() -> new UserNotFoundException(userId));

  if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "WRONG_CURRENT_PASSWORD");
  }

  if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SAME_PASSWORD");
  }

  user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
  userRepository.save(user);
  } 
}
