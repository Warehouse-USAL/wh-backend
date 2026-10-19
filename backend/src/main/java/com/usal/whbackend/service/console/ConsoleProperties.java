package com.usal.whbackend.service.console;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Binds {@code console.*}: where the backend console reads from. */
@Component
@ConfigurationProperties(prefix = "console")
public class ConsoleProperties {

  private String victorialogsUrl = "http://victorialogs:9428";
  private String bridgeUrl = "http://bridge:8081";
  private String minioUrl = "http://minio:9000";
  private String victoriametricsUrl = "http://victoriametrics:8428";
  private String collectorUrl = "http://otel-collector:13133";
  private final Stream stream = new Stream();

  /** The push stream ({@code console.stream.*}). */
  public Stream getStream() {
    return stream;
  }

  public String getVictorialogsUrl() {
    return victorialogsUrl;
  }

  public void setVictorialogsUrl(String victorialogsUrl) {
    this.victorialogsUrl = victorialogsUrl;
  }

  public String getBridgeUrl() {
    return bridgeUrl;
  }

  public void setBridgeUrl(String bridgeUrl) {
    this.bridgeUrl = bridgeUrl;
  }

  public String getMinioUrl() {
    return minioUrl;
  }

  public void setMinioUrl(String minioUrl) {
    this.minioUrl = minioUrl;
  }

  public String getVictoriametricsUrl() {
    return victoriametricsUrl;
  }

  public void setVictoriametricsUrl(String victoriametricsUrl) {
    this.victoriametricsUrl = victoriametricsUrl;
  }

  public String getCollectorUrl() {
    return collectorUrl;
  }

  public void setCollectorUrl(String collectorUrl) {
    this.collectorUrl = collectorUrl;
  }

  /**
   * Intervals and limits of {@code POST /console/stream}. Intervals are server-side only: the
   * browser never polls. Metrics are the one unavoidable pull (VictoriaMetrics has no push), once
   * per unique query however many streams subscribe to it.
   */
  public static class Stream {

    private Duration metricInterval = Duration.ofSeconds(5);
    private Duration logsInterval = Duration.ofSeconds(2);
    private Duration logsOverlap = Duration.ofSeconds(10);
    private Duration statusInterval = Duration.ofSeconds(5);
    private Duration heartbeat = Duration.ofSeconds(15);
    private Duration sendTimeout = Duration.ofSeconds(30);
    private int maxSubscriptions = 200;
    private int maxStreamsPerPrincipal = 10;
    private int maxStreams = 50;
    private int queueCapacity = 1000;

    public Duration getMetricInterval() {
      return metricInterval;
    }

    public void setMetricInterval(Duration metricInterval) {
      this.metricInterval = metricInterval;
    }

    public Duration getLogsInterval() {
      return logsInterval;
    }

    public void setLogsInterval(Duration logsInterval) {
      this.logsInterval = logsInterval;
    }

    /** How far behind the newest line seen each logs query starts, for lines stored late. */
    public Duration getLogsOverlap() {
      return logsOverlap;
    }

    public void setLogsOverlap(Duration logsOverlap) {
      this.logsOverlap = logsOverlap;
    }

    public Duration getStatusInterval() {
      return statusInterval;
    }

    public void setStatusInterval(Duration statusInterval) {
      this.statusInterval = statusInterval;
    }

    public Duration getHeartbeat() {
      return heartbeat;
    }

    public void setHeartbeat(Duration heartbeat) {
      this.heartbeat = heartbeat;
    }

    /** A stream whose client has not accepted a frame for this long is closed. */
    public Duration getSendTimeout() {
      return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
      this.sendTimeout = sendTimeout;
    }

    public int getMaxSubscriptions() {
      return maxSubscriptions;
    }

    public void setMaxSubscriptions(int maxSubscriptions) {
      this.maxSubscriptions = maxSubscriptions;
    }

    public int getMaxStreamsPerPrincipal() {
      return maxStreamsPerPrincipal;
    }

    public void setMaxStreamsPerPrincipal(int maxStreamsPerPrincipal) {
      this.maxStreamsPerPrincipal = maxStreamsPerPrincipal;
    }

    public int getMaxStreams() {
      return maxStreams;
    }

    public void setMaxStreams(int maxStreams) {
      this.maxStreams = maxStreams;
    }

    /** Frames a stream may have waiting for its client; keep it above max-subscriptions. */
    public int getQueueCapacity() {
      return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
      this.queueCapacity = queueCapacity;
    }
  }
}
