package com.usal.whbackend.service;

import com.usal.whbackend.api.user.CreateUserRequest;
import com.usal.whbackend.api.user.UpdateUserRequest;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.repository.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<User> getUsers(String role, Boolean active) {
        throw new UnsupportedOperationException("not implemented");
    }

    public User getUser(String id) {
        throw new UnsupportedOperationException("not implemented");
    }

    public User createUser(CreateUserRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    public User updateUser(String id, UpdateUserRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void resetPassword(String id, String newPassword) {
        throw new UnsupportedOperationException("not implemented");
    }
}
