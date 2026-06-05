package com.usal.whbackend.service.storage;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

  String upload(MultipartFile file, String path);

  void delete(String key);

  StoredObject getObject(String key);

  String getUrl(String key);

  record StoredObject(java.io.InputStream inputStream, String contentType, long contentLength) {}
}
