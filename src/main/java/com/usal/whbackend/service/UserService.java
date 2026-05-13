package com.usal.whbackend.service;

import com.usal.whbackend.api.user.CreateUserRequest;
import com.usal.whbackend.api.user.UpdateUserRequest;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.service.exception.EmailAlreadyExistsException;
import com.usal.whbackend.service.exception.UserNotFoundException;
import java.time.Instant;
import java.util.List;
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

  public List<User> getUsers(String role, Boolean active) {
    if (role != null && active != null) {
      return userRepository.findByRoleAndActive(UserRole.valueOf(role), active);
    }
    if (role != null) {
      return userRepository.findByRole(UserRole.valueOf(role));
    }
    if (active != null) {
      return userRepository.findByActive(active);
    }
    return userRepository.findAll();
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
