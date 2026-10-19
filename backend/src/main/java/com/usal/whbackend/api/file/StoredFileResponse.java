package com.usal.whbackend.api.file;

import com.usal.whbackend.service.storage.StorageService.StoredFile;

public record StoredFileResponse(String path, String filename, long size) {

  static StoredFileResponse from(StoredFile file) {
    return new StoredFileResponse(file.path(), file.filename(), file.size());
  }
}
