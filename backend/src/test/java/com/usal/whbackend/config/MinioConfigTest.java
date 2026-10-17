package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

class MinioConfigTest {

  private MinioConfig config;

  @BeforeEach
  void setUp() {
    config = new MinioConfig();
    config.setEndpoint("http://localhost:9000");
    config.setAccessKey("minioadmin");
    config.setSecretKey("minioadmin");
    config.setBucket("wh-images");
  }

  @Test
  void propertiesRoundTrip() {
    assertThat(config.getEndpoint()).isEqualTo("http://localhost:9000");
    assertThat(config.getAccessKey()).isEqualTo("minioadmin");
    assertThat(config.getSecretKey()).isEqualTo("minioadmin");
    assertThat(config.getBucket()).isEqualTo("wh-images");
  }

  @Test
  void minioClient_isBuiltFromConfiguredProperties() {
    assertThat(config.minioClient()).isNotNull();
  }

  @Test
  void ensureMinioBucket_createsBucketWhenMissing() throws Exception {
    MinioClient client = mock(MinioClient.class);
    when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

    ApplicationRunner runner = config.ensureMinioBucket(client);
    runner.run(mock(ApplicationArguments.class));

    verify(client).makeBucket(any(MakeBucketArgs.class));
  }

  @Test
  void ensureMinioBucket_skipsCreationWhenBucketExists() throws Exception {
    MinioClient client = mock(MinioClient.class);
    when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

    config.ensureMinioBucket(client).run(mock(ApplicationArguments.class));

    verify(client, never()).makeBucket(any(MakeBucketArgs.class));
  }

  @Test
  void ensureMinioBucket_swallowsFailuresSoStartupProceeds() throws Exception {
    MinioClient client = mock(MinioClient.class);
    when(client.bucketExists(any(BucketExistsArgs.class)))
        .thenThrow(new IllegalStateException("MinIO down"));

    ApplicationRunner runner = config.ensureMinioBucket(client);

    assertThatCode(() -> runner.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
    verify(client, never()).makeBucket(any(MakeBucketArgs.class));
  }
}
