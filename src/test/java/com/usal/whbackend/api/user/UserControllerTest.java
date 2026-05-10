package com.usal.whbackend.api.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean UserService userService;

  @Test
  void getUsers_returns200() throws Exception {
    mockMvc.perform(get("/users")).andExpect(status().isOk());
  }

  @Test
  void getUser_returns200() throws Exception {
    mockMvc.perform(get("/users/test-id")).andExpect(status().isOk());
  }

  @Test
  void createUser_returns200() throws Exception {
    mockMvc
        .perform(
            post("/users")
                .contentType("application/json")
                .content(
                    "{\"email\":\"a@b.com\",\"name\":\"Test\",\"role\":\"OPERATOR\",\"initialPassword\":\"pass\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void updateUser_returns200() throws Exception {
    mockMvc
        .perform(
            patch("/users/test-id")
                .contentType("application/json")
                .content("{\"name\":\"Updated\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void resetPassword_returns200() throws Exception {
    mockMvc.perform(post("/users/test-id/reset-password")).andExpect(status().isOk());
  }
}
