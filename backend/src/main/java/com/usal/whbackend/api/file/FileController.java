package com.usal.whbackend.api.file;

import com.usal.whbackend.service.storage.MinioStorageService;
import com.usal.whbackend.service.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
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
          "Stores the file as <path>/<uuid>.<ext> and returns the URL and key. The optional"
              + " `path` part is one segment of 1 to 64 letters, digits, '_' or '-' (default"
              + " `images`); anything else is a 400 BAD_REQUEST."
              + " Requires ADMIN_WAREHOUSE, ADMIN_SALES or SUPERADMIN role.")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE', 'ADMIN_SALES')")
  @PostMapping("/upload")
  public ResponseEntity<FileUploadResponse> upload(
      @RequestParam("file") MultipartFile file,
      @Parameter(
              description = "Folder for the file: 1 to 64 of [A-Za-z0-9_-]; defaults to images",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      pattern = "^[A-Za-z0-9_-]{1,64}$",
                      maxLength = 64))
          @RequestParam(value = "path", required = false)
          String path) {
    String folder = path == null ? IMAGES_PATH : MinioStorageService.uploadPath(path);
    String url = storageService.upload(file, folder);
    String key = MinioStorageService.extractKey(url, REQUEST_BASE);
    return ResponseEntity.ok(new FileUploadResponse(url, key));
  }

  @Operation(
      summary = "List stored files",
      description =
          "SUPERADMIN only. Sorted by path then filename; capped at the first 1000 objects.")
  @PreAuthorize("hasRole('SUPERADMIN')")
  @GetMapping
  public ResponseEntity<Map<String, List<StoredFileResponse>>> list() {
    return ResponseEntity.ok(
        Map.of("files", storageService.list().stream().map(StoredFileResponse::from).toList()));
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
