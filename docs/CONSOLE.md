# Backend console

## Metrics notes

- **No `tomcat.connections.rejected`.** Tomcat (embedded, Spring Boot 4.0.6) exposes no
  rejected-connections attribute. Verified over JMX on a live app: `Tomcat:type=ThreadPool`
  has `currentThreadsBusy`, `currentThreadCount`, `maxThreads`, `connectionCount`,
  `maxConnections`, `acceptCount`, `keepAliveCount`; `Tomcat:type=GlobalRequestProcessor` has
  `requestCount`, `errorCount`, `processingTime`, `maxTime`, `bytesSent`, `bytesReceived`. The only
  `rejected*` attribute anywhere in the `Tomcat` domain is `Manager.rejectedSessions` (HTTP
  sessions, not connections). The Backend page shows `tomcat.connections` current vs max
  saturation instead.
- **Tomcat MBeans are off by default.** Spring Boot disables Tomcat's MBean registry, so
  `tomcat.threads` and `tomcat.connections` need `server.tomcat.mbeanregistry.enabled=true`
  (set as `SERVER_TOMCAT_MBEANREGISTRY_ENABLED` in both compose files). Without it those series
  are absent, not zero.
- **401s carry `route=UNMATCHED`.** Security rejects before handler mapping runs, so there is no
  route template yet. Spring's static-resource catch-all `/**` is also reported as `UNMATCHED`.
- **No `kafka.messages{direction="in"}`.** The backend consumes no Kafka topic; it only
  publishes `orders` and `order.completed` (`direction="out"`).
