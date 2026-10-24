package com.usal.whbackend.api.file;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
class FileControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean StorageService storageService;
  @MockitoBean JwtService jwtService;

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void upload_returnsUrlAndKey() throws Exception {
    when(storageService.upload(any(), anyString())).thenReturn("/api/v1/files/images/uuid.jpg");

    MockMultipartFile file =
        new MockMultipartFile(
            "file", "test.jpg", MediaType.IMAGE_JPEG_VALUE, "fake-image".getBytes());

    mockMvc
        .perform(multipart("/api/v1/files/upload").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("/api/v1/files/images/uuid.jpg"))
        .andExpect(jsonPath("$.key").value("images/uuid.jpg"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void upload_withoutPath_storesUnderImages() throws Exception {
    when(storageService.upload(any(), anyString())).thenReturn("/api/v1/files/images/uuid.jpg");

    mockMvc.perform(multipart("/api/v1/files/upload").file(jpeg())).andExpect(status().isOk());

    verify(storageService).upload(any(), eq("images"));
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void upload_withPath_storesUnderThatPath() throws Exception {
    when(storageService.upload(any(), anyString())).thenReturn("/api/v1/files/products/uuid.jpg");

    mockMvc
        .perform(multipart("/api/v1/files/upload").file(jpeg()).param("path", "products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.key").value("products/uuid.jpg"));

    verify(storageService).upload(any(), eq("products"));
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void upload_withUnsafePath_returnsBadRequestAndStoresNothing() throws Exception {
    for (String path :
        new String[] {"../etc", "a/b", "my.dir", "", "sp ace", "a".repeat(65), "a".repeat(5000)}) {
      mockMvc
          .perform(multipart("/api/v1/files/upload").file(jpeg()).param("path", path))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }
    verifyNoInteractions(storageService);
  }

  private static MockMultipartFile jpeg() {
    return new MockMultipartFile(
        "file", "test.jpg", MediaType.IMAGE_JPEG_VALUE, "fake-image".getBytes());
  }

  @Test
  @WithMockUser
  void serve_returnsImage() throws Exception {
    var obj =
        new StorageService.StoredObject(
            new java.io.ByteArrayInputStream("fake-data".getBytes()),
            MediaType.IMAGE_JPEG_VALUE,
            9);
    when(storageService.getObject("images/test.jpg")).thenReturn(obj);

    mockMvc
        .perform(get("/api/v1/files/images/test.jpg"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.IMAGE_JPEG))
        .andExpect(content().bytes("fake-data".getBytes()));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void delete_returns204() throws Exception {
    doNothing().when(storageService).delete("images/test.jpg");

    mockMvc.perform(delete("/api/v1/files/images/test.jpg")).andExpect(status().isNoContent());
  }

  // ── Path traversal protection ──────────────────────────────────────────────

  @Test
  void serve_withPathTraversal_returnsBadRequest() throws Exception {
    mockMvc.perform(get("/api/v1/files/%2e%2e/test.jpg")).andExpect(status().isBadRequest());
  }

  @Test
  void serve_withEncodedSlash_returnsBadRequest() throws Exception {
    mockMvc.perform(get("/api/v1/files/images/test%2ejpg")).andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void delete_withPathTraversal_returnsBadRequest() throws Exception {
    mockMvc.perform(delete("/api/v1/files/%2e%2e/test.jpg")).andExpect(status().isBadRequest());
  }
}
