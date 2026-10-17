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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "test.jpg",
            MediaType.IMAGE_JPEG_VALUE,
            new byte[] {
              (byte) 0xFF,
              (byte) 0xD8,
              (byte) 0xFF,
              (byte) 0xE0,
              0,
              0x10,
              0x4A,
              0x46,
              0x49,
              0x46,
              0,
              1,
              1,
              0,
              0,
              1,
              0,
              1,
              0,
              0
            });

    String result = storageService.upload(file, "images");

    assertTrue(result.startsWith("/api/v1/files/images/"));
    assertTrue(result.endsWith(".jpg"));
    verify(minioClient).putObject(any(PutObjectArgs.class));
  }

  @Test
  void upload_emptyFile_throwsIllegalArgument() {
    MockMultipartFile file =
        new MockMultipartFile("file", "empty.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[0]);

    assertThrows(IllegalArgumentException.class, () -> storageService.upload(file, "images"));
  }

  @Test
  void upload_unsupportedType_throwsIllegalArgument() {
    MockMultipartFile file =
        new MockMultipartFile("file", "test.gif", "image/gif", "fake-content".getBytes());

    assertThrows(IllegalArgumentException.class, () -> storageService.upload(file, "images"));
  }

  @Test
  void upload_oversizedFile_throwsIllegalArgument() {
    byte[] bigContent = new byte[6 * 1024 * 1024];
    MockMultipartFile file =
        new MockMultipartFile("file", "big.jpg", MediaType.IMAGE_JPEG_VALUE, bigContent);

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

    StorageException thrown =
        assertThrows(StorageException.class, () -> storageService.delete("images/missing.jpg"));

    assertTrue(thrown.getMessage().contains("no existe"));
  }

  @Test
  void getUrl_returnsCorrectFormat() {
    String url = storageService.getUrl("images/uuid.jpg");

    assertEquals("/api/v1/files/images/uuid.jpg", url);
  }

  @Test
  void extractKey_removesRequestBase() {
    String key = MinioStorageService.extractKey("/api/v1/files/images/uuid.jpg", "/api/v1/files/");

    assertEquals("images/uuid.jpg", key);
  }

  @Test
  void extractKey_bareKey_returnsUnchanged() {
    String key = MinioStorageService.extractKey("images/uuid.jpg", "/api/v1/files/");

    assertEquals("images/uuid.jpg", key);
  }

  // ── sanitizePathSegment ──────────────────────────────────────────────────

  @Test
  void sanitizePathSegment_valid_returnsSegment() {
    assertEquals("images", MinioStorageService.sanitizePathSegment("images"));
    assertEquals("avatar123", MinioStorageService.sanitizePathSegment("avatar123"));
    assertEquals("my-photo.jpg", MinioStorageService.sanitizePathSegment("my-photo.jpg"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"..", "../etc", "foo/bar", "foo\\bar", "", " ", "../"})
  void sanitizePathSegment_invalid_throwsIllegalArgument(String segment) {
    assertThrows(
        IllegalArgumentException.class, () -> MinioStorageService.sanitizePathSegment(segment));
  }

  @Test
  void sanitizePathSegment_null_throwsNullPointer() {
    assertThrows(NullPointerException.class, () -> MinioStorageService.sanitizePathSegment(null));
  }

  // ── Magic bytes validation via upload ─────────────────────────────────────

  @Test
  void upload_mismatchedMagicBytes_throwsIllegalArgument() throws Exception {
    // Declares JPEG but contains PNG magic bytes
    byte[] pngBytes = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0};
    MockMultipartFile file =
        new MockMultipartFile("file", "test.jpg", MediaType.IMAGE_JPEG_VALUE, pngBytes);

    assertThrows(IllegalArgumentException.class, () -> storageService.upload(file, "images"));
    verify(minioClient, never()).putObject(any(PutObjectArgs.class));
  }

  @Test
  void upload_invalidMagicBytes_throwsIllegalArgument() throws Exception {
    byte[] garbage = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    MockMultipartFile file =
        new MockMultipartFile("file", "test.jpg", MediaType.IMAGE_JPEG_VALUE, garbage);

    assertThrows(IllegalArgumentException.class, () -> storageService.upload(file, "images"));
    verify(minioClient, never()).putObject(any(PutObjectArgs.class));
  }

  @Test
  void upload_validPngMagicBytes_succeeds() throws Exception {
    byte[] pngBytes = {
      (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0x49, 0x48, 0x44, 0x52
    };
    MockMultipartFile file =
        new MockMultipartFile("file", "test.png", MediaType.IMAGE_PNG_VALUE, pngBytes);

    String result = storageService.upload(file, "images");

    assertTrue(result.endsWith(".png"));
    verify(minioClient).putObject(any(PutObjectArgs.class));
  }

  @Test
  void upload_validWebpMagicBytes_succeeds() throws Exception {
    byte[] webpBytes = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
    MockMultipartFile file = new MockMultipartFile("file", "test.webp", "image/webp", webpBytes);

    String result = storageService.upload(file, "images");

    assertTrue(result.endsWith(".webp"));
    verify(minioClient).putObject(any(PutObjectArgs.class));
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

  @Test
  void delete_otherMinioError_wrapsInStorageException() throws Exception {
    ErrorResponseException ex = mock(ErrorResponseException.class);
    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("AccessDenied");
    when(ex.errorResponse()).thenReturn(errorResponse);
    doThrow(ex).when(minioClient).removeObject(any(RemoveObjectArgs.class));

    StorageException thrown =
        assertThrows(StorageException.class, () -> storageService.delete("images/x.jpg"));

    assertFalse(thrown instanceof FileNotFoundException);
    assertTrue(thrown.getMessage().contains("Error al eliminar"));
  }

  @Test
  void delete_unexpectedFailure_wrapsInStorageException() throws Exception {
    doThrow(new IllegalStateException("network"))
        .when(minioClient)
        .removeObject(any(RemoveObjectArgs.class));

    assertThrows(StorageException.class, () -> storageService.delete("images/x.jpg"));
  }

  @Test
  void deleteByUrl_stripsRequestBaseBeforeDeleting() throws Exception {
    storageService.deleteByUrl("/api/v1/files/images/uuid.jpg");

    org.mockito.ArgumentCaptor<RemoveObjectArgs> captor =
        org.mockito.ArgumentCaptor.forClass(RemoveObjectArgs.class);
    verify(minioClient).removeObject(captor.capture());
    assertEquals("images/uuid.jpg", captor.getValue().object());
  }

  @Test
  void upload_putObjectFailure_wrapsInStorageException() throws Exception {
    doThrow(new IllegalStateException("minio down"))
        .when(minioClient)
        .putObject(any(PutObjectArgs.class));
    MockMultipartFile file = jpeg();

    StorageException thrown =
        assertThrows(StorageException.class, () -> storageService.upload(file, "images"));

    assertTrue(thrown.getMessage().contains("Error al subir archivo"));
  }

  @Test
  void upload_unreadableStream_wrapsInStorageException() throws Exception {
    org.springframework.web.multipart.MultipartFile file =
        mock(org.springframework.web.multipart.MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getContentType()).thenReturn(MediaType.IMAGE_JPEG_VALUE);
    when(file.getSize()).thenReturn(10L);
    when(file.getInputStream()).thenThrow(new java.io.IOException("broken pipe"));

    StorageException thrown =
        assertThrows(StorageException.class, () -> storageService.upload(file, "images"));

    assertTrue(thrown.getMessage().contains("Error al leer archivo"));
  }

  @Test
  void upload_nullContentType_throwsIllegalArgument() {
    MockMultipartFile file = new MockMultipartFile("file", "x.jpg", null, new byte[] {1, 2, 3});

    assertThrows(IllegalArgumentException.class, () -> storageService.upload(file, "images"));
  }

  @Test
  void getObject_usesResponseHeadersWhenPresent() throws Exception {
    GetObjectResponse response = mock(GetObjectResponse.class);
    okhttp3.Headers headers =
        okhttp3.Headers.of("Content-Type", "image/webp", "Content-Length", "1234");
    when(response.headers()).thenReturn(headers);
    when(minioClient.getObject(any())).thenReturn(response);

    StorageService.StoredObject obj = storageService.getObject("images/uuid.bin");

    assertEquals("image/webp", obj.contentType());
    assertEquals(1234L, obj.contentLength());
  }

  @Test
  void getObject_malformedContentLength_fallsBackToZero() throws Exception {
    GetObjectResponse response = mock(GetObjectResponse.class);
    okhttp3.Headers headers =
        okhttp3.Headers.of("Content-Type", "image/jpeg", "Content-Length", "not-a-number");
    when(response.headers()).thenReturn(headers);
    when(minioClient.getObject(any())).thenReturn(response);

    assertEquals(0L, storageService.getObject("images/uuid.jpg").contentLength());
  }

  @Test
  void getObject_headersWithoutContentType_infersFromKey() throws Exception {
    GetObjectResponse response = mock(GetObjectResponse.class);
    when(response.headers()).thenReturn(okhttp3.Headers.of("Content-Length", "7"));
    when(minioClient.getObject(any())).thenReturn(response);

    StorageService.StoredObject obj = storageService.getObject("images/uuid.jpeg");

    assertEquals("image/jpeg", obj.contentType());
    assertEquals(7L, obj.contentLength());
  }

  @Test
  void getObject_unknownExtension_fallsBackToOctetStream() throws Exception {
    GetObjectResponse response = mock(GetObjectResponse.class);
    when(response.headers()).thenReturn(null);
    when(minioClient.getObject(any())).thenReturn(response);

    assertEquals(
        "application/octet-stream", storageService.getObject("images/uuid.txt").contentType());
  }

  @Test
  void getObject_webpExtension_isInferred() throws Exception {
    GetObjectResponse response = mock(GetObjectResponse.class);
    when(response.headers()).thenReturn(null);
    when(minioClient.getObject(any())).thenReturn(response);

    assertEquals("image/webp", storageService.getObject("images/uuid.webp").contentType());
  }

  @Test
  void getObject_missingKey_throwsFileNotFound() throws Exception {
    ErrorResponseException ex = mock(ErrorResponseException.class);
    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("NoSuchKey");
    when(ex.errorResponse()).thenReturn(errorResponse);
    when(minioClient.getObject(any())).thenThrow(ex);

    assertThrows(FileNotFoundException.class, () -> storageService.getObject("images/gone.jpg"));
  }

  @Test
  void getObject_otherMinioError_wrapsInStorageException() throws Exception {
    ErrorResponseException ex = mock(ErrorResponseException.class);
    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("AccessDenied");
    when(ex.errorResponse()).thenReturn(errorResponse);
    when(minioClient.getObject(any())).thenThrow(ex);

    StorageException thrown =
        assertThrows(StorageException.class, () -> storageService.getObject("images/x.jpg"));
    assertFalse(thrown instanceof FileNotFoundException);
  }

  @Test
  void getObject_unexpectedFailure_wrapsInStorageException() throws Exception {
    when(minioClient.getObject(any())).thenThrow(new IllegalStateException("network"));

    assertThrows(StorageException.class, () -> storageService.getObject("images/x.jpg"));
  }

  @Test
  void upload_truncatedPngHeader_throwsIllegalArgument() {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "x.png", MediaType.IMAGE_PNG_VALUE, new byte[] {(byte) 0x89, 'P', 'N'});

    assertThrows(IllegalArgumentException.class, () -> storageService.upload(file, "images"));
  }

  @Test
  void upload_truncatedWebpHeader_throwsIllegalArgument() {
    MockMultipartFile file =
        new MockMultipartFile("file", "x.webp", "image/webp", "RIFF1234".getBytes());

    assertThrows(IllegalArgumentException.class, () -> storageService.upload(file, "images"));
  }

  @Test
  void storageException_retainsCause() {
    IllegalStateException cause = new IllegalStateException("root");
    StorageException ex = new StorageException("wrapped", cause);

    assertEquals("wrapped", ex.getMessage());
    assertSame(cause, ex.getCause());
  }

  private static MockMultipartFile jpeg() {
    return new MockMultipartFile(
        "file",
        "test.jpg",
        MediaType.IMAGE_JPEG_VALUE,
        new byte[] {
          (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0, 1
        });
  }
}
