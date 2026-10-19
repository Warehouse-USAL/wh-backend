package com.usal.whbackend.service.console.stream;

import static com.usal.whbackend.service.console.stream.StreamFixtures.subscriber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.usal.whbackend.service.console.ServiceStatus;
import com.usal.whbackend.service.console.ServiceStatusService;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusSourceTest {

  private final ServiceStatusService service = mock(ServiceStatusService.class);
  private final StatusSource source = new StatusSource(service, StreamFixtures.JSON);
  private final RecordingSink sink = new RecordingSink();

  private static List<ServiceStatus> rows(String mongo, long uptime) {
    return List.of(
        new ServiceStatus("backend", "UP", null, uptime),
        new ServiceStatus("mongodb", mongo, mongo.equals("UP") ? null : "timeout", null));
  }

  @Test
  void sendsTheRestPayloadOnSubscribeAndAgainOnlyWhenAStateChanges() {
    when(service.isDev()).thenReturn(true);
    when(service.statuses())
        .thenReturn(rows("UP", 1000), rows("UP", 6000), rows("DOWN", 11000), rows("DOWN", 16000));
    source.join(subscriber("st", sink));

    for (int i = 0; i < 4; i++) {
      source.evaluate();
    }

    // The backend uptime grows on every probe; on its own it is not a change.
    assertThat(sink.frames())
        .containsExactly(
            "event: snapshot\ndata: {\"id\":\"st\",\"mode\":\"replace\",\"data\":{\"dev\":true,"
                + "\"services\":[{\"name\":\"backend\",\"state\":\"UP\",\"detail\":null,"
                + "\"uptime_ms\":1000},{\"name\":\"mongodb\",\"state\":\"UP\",\"detail\":null,"
                + "\"uptime_ms\":null}]}}\n\n",
            "event: update\ndata: {\"id\":\"st\",\"mode\":\"replace\",\"data\":{\"dev\":true,"
                + "\"services\":[{\"name\":\"backend\",\"state\":\"UP\",\"detail\":null,"
                + "\"uptime_ms\":11000},{\"name\":\"mongodb\",\"state\":\"DOWN\","
                + "\"detail\":\"timeout\",\"uptime_ms\":null}]}}\n\n");
  }
}
