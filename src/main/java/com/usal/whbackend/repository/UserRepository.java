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
