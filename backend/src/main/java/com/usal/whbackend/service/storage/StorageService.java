package com.usal.whbackend.service.storage;

import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

  String upload(MultipartFile file, String path);

  void delete(String key);

  void deleteByUrl(String url);

  StoredObject getObject(String key);

  String getUrl(String key);

  /**
   * Lists stored objects sorted by path then filename. Bounded: at most the first 1000 objects in
   * bucket order are returned, so a huge bucket cannot exhaust memory.
   */
  List<StoredFile> list();

  record StoredFile(String path, String filename, long size) {}

  record StoredObject(java.io.InputStream inputStream, String contentType, long contentLength) {}
}
