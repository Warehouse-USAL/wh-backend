package com.usal.whbackend.api.file;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.service.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileController.class)
@EnableMethodSecurity
@Import(GlobalExceptionHandler.class)
class FileControllerSecurityTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean StorageService storageService;
  @MockitoBean JwtService jwtService;

  private final MockMultipartFile fakeFile = new MockMultipartFile(
      "file", "test.jpg", MediaType.IMAGE_JPEG_VALUE, "data".getBytes());

  @Test
  @WithMockUser(roles = "PROVIDER")
  void upload_withProvider_returns403() throws Exception {
    mockMvc
        .perform(multipart("/api/v1/files/upload").file(fakeFile))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "DISPATCHER")
  void upload_withDispatcher_returns403() throws Exception {
    mockMvc
        .perform(multipart("/api/v1/files/upload").file(fakeFile))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SALES")
  void upload_withAdminSales_returns200() throws Exception {
    org.mockito.BDDMockito.given(storageService.upload(any(), anyString()))
        .willReturn("/api/v1/files/images/uuid.jpg");
    mockMvc
        .perform(multipart("/api/v1/files/upload").file(fakeFile))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void upload_withAdminWarehouse_returns200() throws Exception {
    org.mockito.BDDMockito.given(storageService.upload(any(), anyString()))
        .willReturn("/api/v1/files/images/uuid.jpg");
    mockMvc
        .perform(multipart("/api/v1/files/upload").file(fakeFile))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void upload_withSuperadmin_returns200() throws Exception {
    org.mockito.BDDMockito.given(storageService.upload(any(), anyString()))
        .willReturn("/api/v1/files/images/uuid.jpg");
    mockMvc
        .perform(multipart("/api/v1/files/upload").file(fakeFile))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SALES")
  void delete_withAdminSales_returns403() throws Exception {
    mockMvc
        .perform(delete("/api/v1/files/images/test.jpg"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void delete_withAdminWarehouse_returns204() throws Exception {
    mockMvc
        .perform(delete("/api/v1/files/images/test.jpg"))
        .andExpect(status().isNoContent());
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void delete_withSuperadmin_returns204() throws Exception {
    mockMvc
        .perform(delete("/api/v1/files/images/test.jpg"))
        .andExpect(status().isNoContent());
  }

  @Test
  void serve_withoutAuth_returns200() throws Exception {
    var obj = new StorageService.StoredObject(
        new java.io.ByteArrayInputStream("data".getBytes()),
        MediaType.IMAGE_JPEG_VALUE,
        4);
    org.mockito.BDDMockito.given(storageService.getObject("images/test.jpg")).willReturn(obj);

    mockMvc
        .perform(get("/api/v1/files/images/test.jpg"))
        .andExpect(status().isOk());
  }
}
