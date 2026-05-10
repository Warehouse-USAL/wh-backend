package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.usal.whbackend.api.user.CreateUserRequest;
import com.usal.whbackend.api.user.UpdateUserRequest;
import com.usal.whbackend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @InjectMocks UserService userService;

    @Test
    void getUsers_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> userService.getUsers(null, null));
    }

    @Test
    void getUser_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> userService.getUser("id-1"));
    }

    @Test
    void createUser_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> userService.createUser(new CreateUserRequest(null, null, null, null)));
    }

    @Test
    void updateUser_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> userService.updateUser("id-1", new UpdateUserRequest(null, null, null)));
    }

    @Test
    void resetPassword_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> userService.resetPassword("id-1", "newpass"));
    }
}
