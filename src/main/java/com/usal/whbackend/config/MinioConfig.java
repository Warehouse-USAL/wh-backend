package com.usal.whbackend.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  @PostConstruct
  public void ensureBucketExists() {
    try {
      var client = minioClient();
      boolean exists = client.bucketExists(
          BucketExistsArgs.builder().bucket(bucket).build());
      if (!exists) {
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        log.info("Bucket '{}' created successfully", bucket);
      }
    } catch (Exception e) {
      log.warn("Could not verify/create bucket '{}': {}. Uploads will fail until MinIO is available.",
          bucket, e.getMessage());
    }
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
