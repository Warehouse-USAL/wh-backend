package com.usal.whbackend.service.storage;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MinioStorageService implements StorageService {

  private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);
  private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
  private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
  static final int MAX_LISTED_FILES = 1000;
  private static final int MAGIC_BYTES_LENGTH = 12;
  private static final Pattern SAFE_SEGMENT = Pattern.compile("[a-zA-Z0-9._-]+");
  private static final Pattern UPLOAD_PATH = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private final MinioClient minioClient;
  private final String bucket;
  private final String requestBase;

  public MinioStorageService(
      MinioClient minioClient,
      @Value("${minio.bucket}") String bucket,
      @Value("${minio.request-base:/api/v1/files/}") String requestBase) {
    this.minioClient = minioClient;
    this.bucket = bucket;
    this.requestBase = requestBase;
  }

  @Override
  public String upload(MultipartFile file, String path) {
    if (file.isEmpty()) {
      throw new IllegalArgumentException("El archivo está vacío");
    }

    String contentType = file.getContentType();
    if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
      throw new IllegalArgumentException(
          "Formato no soportado: " + contentType + ". Solo se aceptan JPEG, PNG y WebP.");
    }

    if (file.getSize() > MAX_FILE_SIZE) {
      throw new IllegalArgumentException("El archivo supera el tamaño máximo de 5MB.");
    }

    // Validate actual file content via magic bytes (not just the Content-Type header)
    byte[] header = new byte[MAGIC_BYTES_LENGTH];
    int bytesRead;
    try (InputStream is = file.getInputStream()) {
      bytesRead = is.read(header);
    } catch (IOException e) {
      throw new StorageException("Error al leer archivo", e);
    }
    validateMagicBytes(header, bytesRead, contentType);

    String extension =
        switch (contentType) {
          case "image/jpeg" -> ".jpg";
          case "image/png" -> ".png";
          case "image/webp" -> ".webp";
          default -> throw new IllegalArgumentException("Formato no soportado");
        };
    String key = path + "/" + UUID.randomUUID() + extension;

    try (InputStream inputStream = file.getInputStream()) {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(key).stream(inputStream, file.getSize(), -1)
              .contentType(contentType)
              .build());
      log.info("Uploaded file: {} ({} bytes, {})", key, file.getSize(), contentType);
    } catch (Exception e) {
      throw new StorageException("Error al subir archivo: " + key, e);
    }

    return getUrl(key);
  }

  @Override
  public void delete(String key) {
    try {
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
      log.info("Deleted file: {}", key);
    } catch (ErrorResponseException e) {
      if ("NoSuchKey".equals(e.errorResponse().code())) {
        throw new FileNotFoundException(key);
      }
      throw new StorageException("Error al eliminar archivo: " + key, e);
    } catch (Exception e) {
      throw new StorageException("Error al eliminar archivo: " + key, e);
    }
  }

  @Override
  public StoredObject getObject(String key) {
    try {
      GetObjectResponse response =
          minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
      var hdrs = response.headers();
      String contentType = hdrs != null ? hdrs.get("Content-Type") : inferContentType(key);
      long contentLength = 0L;
      if (hdrs != null && hdrs.get("Content-Length") != null) {
        try {
          contentLength = Long.parseLong(hdrs.get("Content-Length"));
        } catch (NumberFormatException ignored) {
        }
      }
      if (contentType == null) contentType = inferContentType(key);
      return new StoredObject(response, contentType, contentLength);
    } catch (ErrorResponseException e) {
      if ("NoSuchKey".equals(e.errorResponse().code())) {
        throw new FileNotFoundException(key);
      }
      throw new StorageException("Error al leer archivo: " + key, e);
    } catch (Exception e) {
      throw new StorageException("Error al leer archivo: " + key, e);
    }
  }

  @Override
  public void deleteByUrl(String url) {
    delete(extractKey(url, requestBase));
  }

  @Override
  public String getUrl(String key) {
    return requestBase + key;
  }

  @Override
  public Listing list() {
    List<StoredFile> files = new ArrayList<>();
    boolean truncated;
    try {
      Iterator<Result<Item>> objects =
          minioClient
              .listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())
              .iterator();
      while (files.size() < MAX_LISTED_FILES && objects.hasNext()) {
        Item item = objects.next().get();
        if (item.isDir()) {
          continue;
        }
        String key = item.objectName();
        int slash = key.lastIndexOf('/');
        files.add(
            new StoredFile(
                slash < 0 ? "" : key.substring(0, slash), key.substring(slash + 1), item.size()));
      }
      truncated = objects.hasNext();
    } catch (Exception e) {
      throw new StorageException("Error al listar archivos", e);
    }
    files.sort(Comparator.comparing(StoredFile::path).thenComparing(StoredFile::filename));
    return new Listing(files, truncated);
  }

  public static String extractKey(String url, String requestBase) {
    int idx = url.indexOf(requestBase);
    return idx != -1 ? url.substring(idx + requestBase.length()) : url;
  }

  /**
   * An upload folder: one segment of 1 to 64 letters, digits, '_' or '-' (no dots, so no traversal;
   * bounded, so an over-long folder is a 400 here rather than a storage failure). The rejected
   * value is not echoed back.
   */
  public static String uploadPath(String path) {
    if (!UPLOAD_PATH.matcher(path).matches()) {
      throw new IllegalArgumentException(
          "Invalid path: use 1 to 64 letters, digits, '_' or '-' only.");
    }
    return path;
  }

  public static String sanitizePathSegment(String segment) {
    Objects.requireNonNull(segment, "Path segment must not be null");
    if (segment.isBlank()) {
      throw new IllegalArgumentException("Path segment must not be blank");
    }
    if (segment.contains("..") || segment.contains("/") || segment.contains("\\")) {
      throw new IllegalArgumentException(
          "Invalid path segment: '" + segment + "' contains '..', '/', or '\\'");
    }
    if (!SAFE_SEGMENT.matcher(segment).matches()) {
      throw new IllegalArgumentException(
          "Invalid path segment: '" + segment + "' contains unsupported characters");
    }
    return segment;
  }

  private static void validateMagicBytes(byte[] data, int length, String contentType) {
    boolean valid =
        switch (contentType) {
          case "image/jpeg" ->
              length >= 3
                  && (data[0] & 0xFF) == 0xFF
                  && (data[1] & 0xFF) == 0xD8
                  && (data[2] & 0xFF) == 0xFF;
          case "image/png" ->
              length >= 8
                  && (data[0] & 0xFF) == 0x89
                  && data[1] == 'P'
                  && data[2] == 'N'
                  && data[3] == 'G'
                  && (data[4] & 0xFF) == 0x0D
                  && (data[5] & 0xFF) == 0x0A
                  && (data[6] & 0xFF) == 0x1A
                  && (data[7] & 0xFF) == 0x0A;
          case "image/webp" ->
              length >= 12
                  && data[0] == 'R'
                  && data[1] == 'I'
                  && data[2] == 'F'
                  && data[3] == 'F'
                  && data[8] == 'W'
                  && data[9] == 'E'
                  && data[10] == 'B'
                  && data[11] == 'P';
          default -> false;
        };
    if (!valid) {
      throw new IllegalArgumentException(
          "El contenido del archivo no coincide con el formato declarado: " + contentType);
    }
  }

  private String inferContentType(String key) {
    if (key.endsWith(".jpg") || key.endsWith(".jpeg")) return "image/jpeg";
    if (key.endsWith(".png")) return "image/png";
    if (key.endsWith(".webp")) return "image/webp";
    return "application/octet-stream";
  }
}
