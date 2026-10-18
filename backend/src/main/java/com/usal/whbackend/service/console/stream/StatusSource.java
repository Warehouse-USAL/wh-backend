package com.usal.whbackend.service.console.stream;

import com.usal.whbackend.service.console.ServiceStatus;
import com.usal.whbackend.service.console.ServiceStatusService;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code GET /console/status} payload, probed by the shared loop (the status service keeps its
 * one-probe-per-service in-flight guard). The backend's {@code uptime_ms} grows on every probe, so
 * it is left out of the change comparison: a frame is pushed when a state or detail changes, and
 * the client can advance the uptime it last received.
 */
final class StatusSource extends ReplaceSource {

  /** Same fields, in the same order, as the REST response. */
  record Payload(boolean dev, List<ServiceStatus> services) {}

  private final ServiceStatusService service;
  private final JsonMapper json;

  StatusSource(ServiceStatusService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @Override
  protected Result fetch() {
    List<ServiceStatus> rows = service.statuses();
    boolean dev = service.isDev();
    List<ServiceStatus> withoutUptime =
        rows.stream().map(r -> new ServiceStatus(r.name(), r.state(), r.detail(), null)).toList();
    return new Result(
        json.writeValueAsString(new Payload(dev, rows)),
        json.writeValueAsString(new Payload(dev, withoutUptime)));
  }
}
