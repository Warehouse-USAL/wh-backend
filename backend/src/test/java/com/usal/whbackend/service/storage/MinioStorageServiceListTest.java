package com.usal.whbackend.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MinioStorageServiceListTest {

  MinioClient minioClient = mock(MinioClient.class);
  MinioStorageService storageService;

  @BeforeEach
  void setUp() {
    storageService = new MinioStorageService(minioClient, "wh-images", "/api/v1/files/");
  }

  private static Result<Item> result(String key, long size) throws Exception {
    Item item = mock(Item.class);
    when(item.objectName()).thenReturn(key);
    when(item.size()).thenReturn(size);
    Result<Item> result = mock(Result.class);
    when(result.get()).thenReturn(item);
    return result;
  }

  @Test
  void listsObjectsSplittingPathAndFilename() throws Exception {
    List<Result<Item>> found = List.of(result("products/pallet-jack.png", 42L));
    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(found);

    List<StorageService.StoredFile> files = storageService.list();

    assertEquals(List.of(new StorageService.StoredFile("products", "pallet-jack.png", 42L)), files);
  }

  @Test
  void keyWithoutSlashHasEmptyPathAndNestedKeysKeepFullPath() throws Exception {
    List<Result<Item>> found = List.of(result("a/b/c.png", 3L), result("root.png", 1L));
    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(found);

    List<StorageService.StoredFile> files = storageService.list();

    assertEquals("", files.get(0).path());
    assertEquals("root.png", files.get(0).filename());
    assertEquals("a/b", files.get(1).path());
    assertEquals("c.png", files.get(1).filename());
  }

  @Test
  void sortsByPathThenFilename() throws Exception {
    List<Result<Item>> found =
        List.of(result("b/a.png", 1), result("a/z.png", 1), result("a/b.png", 1));
    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(found);

    List<String> order =
        storageService.list().stream().map(f -> f.path() + "/" + f.filename()).toList();

    assertEquals(List.of("a/b.png", "a/z.png", "b/a.png"), order);
  }

  @Test
  void stopsAtTheCapWithoutConsumingTheRestOfTheListing() throws Exception {
    AtomicInteger pulled = new AtomicInteger();
    Iterable<Result<Item>> endless =
        () ->
            new Iterator<>() {
              @Override
              public boolean hasNext() {
                return true;
              }

              @Override
              public Result<Item> next() {
                try {
                  return result("p/f" + pulled.incrementAndGet() + ".png", 1);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              }
            };
    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(endless);

    List<StorageService.StoredFile> files = storageService.list();

    assertEquals(MinioStorageService.MAX_LISTED_FILES, files.size());
    assertEquals(MinioStorageService.MAX_LISTED_FILES, pulled.get());
  }

  @Test
  void listingFailureBecomesStorageException() throws Exception {
    Result<Item> broken = mock(Result.class);
    when(broken.get()).thenThrow(new java.io.IOException("boom"));
    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(broken));

    StorageException e = assertThrows(StorageException.class, () -> storageService.list());

    assertTrue(e.getMessage().startsWith("Error al listar archivos"));
  }

  @Test
  void skipsDirectoryMarkers() throws Exception {
    Item dir = mock(Item.class);
    when(dir.isDir()).thenReturn(true);
    Result<Item> dirResult = mock(Result.class);
    when(dirResult.get()).thenReturn(dir);
    List<Result<Item>> all = new ArrayList<>(List.of(dirResult, result("x/y.png", 1)));
    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(all);

    assertEquals(1, storageService.list().size());
  }
}
