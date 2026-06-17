package com.usal.whbackend.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {

  private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

  private String endpoint;
  private String accessKey;
  private String secretKey;
  private String bucket;

  @Bean
  public MinioClient minioClient() {
    return MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .build();
  }

  /**
   * Ensures the bucket exists at startup. Implemented as an ApplicationRunner that receives the
   * already-built {@link MinioClient} bean, rather than a {@code @PostConstruct} that calls the
   * {@code minioClient()} @Bean method on this same @Configuration — the latter triggers a Spring
   * "bean is currently in creation" circular reference, so the bucket was never actually created
   * and the first upload failed.
   */
  @Bean
  public ApplicationRunner ensureMinioBucket(MinioClient client) {
    return args -> {
      try {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
          client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
          log.info("Bucket '{}' created successfully", bucket);
        }
      } catch (Exception e) {
        log.warn("Could not verify/create bucket '{}': {}. Uploads will fail until MinIO is available.",
            bucket, e.getMessage());
      }
    };
  }

  public String getEndpoint() { return endpoint; }
  public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
  public String getAccessKey() { return accessKey; }
  public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
  public String getSecretKey() { return secretKey; }
  public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
  public String getBucket() { return bucket; }
  public void setBucket(String bucket) { this.bucket = bucket; }
}
