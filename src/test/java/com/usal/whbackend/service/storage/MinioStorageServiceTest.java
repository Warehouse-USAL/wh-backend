package com.usal.whbackend.service.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class MinioStorageServiceTest {

  @Mock MinioClient minioClient;
  MinioStorageService storageService;

  @BeforeEach
  void setUp() {
    storageService = new MinioStorageService(minioClient, "wh-images", "/api/v1/files/");
  }

  @Test
  void upload_validImage_returnsUrl() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file", "test.jpg", MediaType.IMAGE_JPEG_VALUE, "fake-image-content".getBytes());

    String result = storageService.upload(file, "images");

    assertTrue(result.startsWith("/api/v1/files/images/"));
    assertTrue(result.endsWith(".jpg"));
    verify(minioClient).putObject(any(PutObjectArgs.class));
  }

  @Test
  void upload_emptyFile_throwsIllegalArgument() {
    MockMultipartFile file = new MockMultipartFile(
        "file", "empty.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[0]);

    assertThrows(IllegalArgumentException.class, () -> storageService.upload(file, "images"));
  }

  @Test
  void upload_unsupportedType_throwsIllegalArgument() {
    MockMultipartFile file = new MockMultipartFile(
        "file", "test.gif", "image/gif", "fake-content".getBytes());

    assertThrows(IllegalArgumentException.class, () -> storageService.upload(file, "images"));
  }

  @Test
  void upload_oversizedFile_throwsIllegalArgument() {
    byte[] bigContent = new byte[6 * 1024 * 1024];
    MockMultipartFile file = new MockMultipartFile(
        "file", "big.jpg", MediaType.IMAGE_JPEG_VALUE, bigContent);

    assertThrows(IllegalArgumentException.class, () -> storageService.upload(file, "images"));
  }

  @Test
  void delete_existingKey_succeeds() throws Exception {
    storageService.delete("images/uuid.jpg");

    verify(minioClient).removeObject(any(RemoveObjectArgs.class));
  }

  @Test
  void delete_nonExistingKey_throwsStorageException() throws Exception {
    ErrorResponseException ex = mock(ErrorResponseException.class);
    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("NoSuchKey");
    when(ex.errorResponse()).thenReturn(errorResponse);
    doThrow(ex).when(minioClient).removeObject(any(RemoveObjectArgs.class));

    StorageException thrown = assertThrows(StorageException.class,
        () -> storageService.delete("images/missing.jpg"));

    assertTrue(thrown.getMessage().contains("no existe"));
  }

  @Test
  void getUrl_returnsCorrectFormat() {
    String url = storageService.getUrl("images/uuid.jpg");

    assertEquals("/api/v1/files/images/uuid.jpg", url);
  }

  @Test
  void extractKey_removesRequestBase() {
    String key = MinioStorageService.extractKey(
        "/api/v1/files/images/uuid.jpg", "/api/v1/files/");

    assertEquals("images/uuid.jpg", key);
  }

  @Test
  void extractKey_bareKey_returnsUnchanged() {
    String key = MinioStorageService.extractKey("images/uuid.jpg", "/api/v1/files/");

    assertEquals("images/uuid.jpg", key);
  }

  @Test
  void getObject_infersContentTypeFromExtension() throws Exception {
    InputStream fakeStream = new ByteArrayInputStream("data".getBytes());
    GetObjectResponse response = mock(GetObjectResponse.class);
    when(response.headers()).thenReturn(null);
    when(minioClient.getObject(any())).thenReturn(response);

    StorageService.StoredObject obj = storageService.getObject("images/uuid.png");

    assertEquals("image/png", obj.contentType());
    assertEquals(0, obj.contentLength());
    assertNotNull(obj.inputStream());
  }
}
