package com.usal.whbackend.api.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.service.UserService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
@EnableMethodSecurity
@Import(GlobalExceptionHandler.class)
class UserControllerSecurityTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean UserService userService;
    @MockitoBean JwtService jwtService;

    @Test
    @WithMockUser(roles = "ADMIN_SALES")
    void getUsers_withNonAdminSystem_returns403() throws Exception {
        mockMvc.perform(get("/users"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN_SYSTEM")
    void getUsers_withAdminSystem_returns200() throws Exception {
        when(userService.getUsers(any(), any())).thenReturn(List.of());
        mockMvc.perform(get("/users"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN_SALES")
    void getUser_withNonAdminSystem_returns403() throws Exception {
        mockMvc.perform(get("/users/some-id"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN_SALES")
    void createUser_withNonAdminSystem_returns403() throws Exception {
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@test.com\",\"name\":\"Test\",\"role\":\"OPERATOR\",\"initial_password\":\"pass\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN_SALES")
    void updateUser_withNonAdminSystem_returns403() throws Exception {
        mockMvc.perform(patch("/users/some-id")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New Name\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN_SALES")
    void resetPassword_withNonAdminSystem_returns403() throws Exception {
        mockMvc.perform(post("/users/some-id/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"new_password\":\"newpass123\"}"))
            .andExpect(status().isForbidden());
    }
}
