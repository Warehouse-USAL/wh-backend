package com.usal.whbackend.api.file;

import com.usal.whbackend.service.storage.MinioStorageService;
import com.usal.whbackend.service.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "Files", description = "Image upload, serving and management")
@SecurityRequirement(name = "bearer-jwt")
public class FileController {

  private static final String IMAGES_PATH = "images";
  private static final String REQUEST_BASE = "/api/v1/files/";

  private final StorageService storageService;

  public FileController(StorageService storageService) {
    this.storageService = storageService;
  }

  @Operation(
      summary = "Upload an image",
      description =
          "Returns the URL and key. Requires ADMIN_WAREHOUSE, ADMIN_SALES or SUPERADMIN role.")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE', 'ADMIN_SALES')")
  @PostMapping("/upload")
  public ResponseEntity<FileUploadResponse> upload(@RequestParam("file") MultipartFile file) {
    String url = storageService.upload(file, IMAGES_PATH);
    String key = MinioStorageService.extractKey(url, REQUEST_BASE);
    return ResponseEntity.ok(new FileUploadResponse(url, key));
  }

  @Operation(
      summary = "Serve an image by path and filename",
      description = "Proxies the image from MinIO. Public access (no auth required).")
  @GetMapping("/{path}/{filename}")
  public ResponseEntity<InputStreamResource> serve(
      @PathVariable String path, @PathVariable String filename) {
    MinioStorageService.sanitizePathSegment(path);
    MinioStorageService.sanitizePathSegment(filename);
    String key = path + "/" + filename;
    StorageService.StoredObject obj = storageService.getObject(key);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(obj.contentType()))
        .contentLength(obj.contentLength())
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic())
        .body(new InputStreamResource(obj.inputStream()));
  }

  @Operation(
      summary = "Delete an image by path and filename",
      description = "Requires ADMIN_WAREHOUSE or SUPERADMIN role.")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
  @DeleteMapping("/{path}/{filename}")
  public ResponseEntity<Void> delete(@PathVariable String path, @PathVariable String filename) {
    MinioStorageService.sanitizePathSegment(path);
    MinioStorageService.sanitizePathSegment(filename);
    storageService.delete(path + "/" + filename);
    return ResponseEntity.noContent().build();
  }
}
