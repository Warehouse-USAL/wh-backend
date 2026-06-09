package com.usal.whbackend.service.storage;

public class FileNotFoundException extends StorageException {
  public FileNotFoundException(String key) {
    super("El archivo no existe: " + key);
  }
}
