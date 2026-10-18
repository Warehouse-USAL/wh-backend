package com.usal.whbackend.api.file;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.service.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(FileController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class FileControllerListTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean StorageService storageService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void superadminGetsFilesInSnakeCaseEnvelope() throws Exception {
    when(storageService.list())
        .thenReturn(List.of(new StorageService.StoredFile("products", "pallet-jack.png", 42L)));

    mockMvc
        .perform(get("/api/v1/files"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files[0].path").value("products"))
        .andExpect(jsonPath("$.files[0].filename").value("pallet-jack.png"))
        .andExpect(jsonPath("$.files[0].size").value(42));
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void nonSuperadminGets403() throws Exception {
    mockMvc.perform(get("/api/v1/files")).andExpect(status().isForbidden());
    verifyNoInteractions(storageService);
  }

  @Test
  void anonymousGets401() throws Exception {
    mockMvc.perform(get("/api/v1/files")).andExpect(status().isUnauthorized());
    verifyNoInteractions(storageService);
  }

  @Test
  void downloadRouteStaysPublic() throws Exception {
    when(storageService.getObject("images/a.png"))
        .thenReturn(
            new StorageService.StoredObject(
                new ByteArrayInputStream(new byte[] {1}), MediaType.IMAGE_PNG_VALUE, 1));

    mockMvc.perform(get("/api/v1/files/images/a.png")).andExpect(status().isOk());
    verify(storageService).getObject("images/a.png");
  }
}
