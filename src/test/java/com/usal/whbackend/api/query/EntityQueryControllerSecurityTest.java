package com.usal.whbackend.api.query;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.service.query.EntityQueryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EntityQueryController.class)
@EnableMethodSecurity
@Import(GlobalExceptionHandler.class)
class EntityQueryControllerSecurityTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean EntityQueryService entityQueryService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient().when(entityQueryService.catalog(any())).thenReturn(List.of());
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void authenticatedCallerReachesTheCatalogue() throws Exception {
    mockMvc.perform(get("/query/catalog")).andExpect(status().isOk());
  }

  @Test
  void anonymousCallerNeverReachesTheService() {
    // This slice has method security but not the real filter chain, so an anonymous request
    // raises rather than returning 401 — the 401 mapping is SecurityConfig's job and is covered
    // by SecurityConfigTest. What matters here is that the method gate refuses to let the call
    // through at all.
    assertThatThrownBy(() -> mockMvc.perform(get("/query/catalog")))
        .rootCause()
        .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

    verifyNoInteractions(entityQueryService);
  }
}
