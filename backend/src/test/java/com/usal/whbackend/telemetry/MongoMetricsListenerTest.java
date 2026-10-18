package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import com.mongodb.event.ConnectionCheckedInEvent;
import com.mongodb.event.ConnectionCheckedOutEvent;
import com.mongodb.event.ConnectionClosedEvent;
import com.mongodb.event.ConnectionCreatedEvent;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MongoMetricsListenerTest {

  private TelemetryPort port;
  private TelemetryProperties props;
  private MongoMetricsListener listener;

  @BeforeEach
  void setUp() {
    port = mock(TelemetryPort.class);
    props = new TelemetryProperties();
    listener = new MongoMetricsListener(port, props);
  }

  private void started(int requestId, String command, String collection) {
    listener.commandStarted(
        new CommandStartedEvent(
            null,
            1L,
            requestId,
            null,
            "wh",
            command,
            new BsonDocument(command, new org.bson.BsonString(collection))));
  }

  private void succeeded(int requestId, String command, long millis) {
    listener.commandSucceeded(
        new CommandSucceededEvent(
            null,
            1L,
            requestId,
            null,
            "wh",
            command,
            new BsonDocument(),
            TimeUnit.MILLISECONDS.toNanos(millis)));
  }

  private MongoCommandSample recorded() {
    ArgumentCaptor<MongoCommandSample> captor = ArgumentCaptor.forClass(MongoCommandSample.class);
    verify(port).recordMongoCommand(captor.capture());
    return captor.getValue();
  }

  @Test
  void pendingCommandsAreCappedAndFinishingOnesFreeRoom() {
    for (int id = 0; id < 10_000; id++) {
      started(id, "find", "orders");
    }
    started(10_000, "find", "dropped");

    succeeded(10_000, "find", 1);
    verify(port, never()).recordMongoCommand(any());

    succeeded(0, "find", 1);
    verify(port).recordMongoCommand(any());

    started(10_001, "find", "admitted");
    succeeded(10_001, "find", 1);
    ArgumentCaptor<MongoCommandSample> captor = ArgumentCaptor.forClass(MongoCommandSample.class);
    verify(port, org.mockito.Mockito.times(2)).recordMongoCommand(captor.capture());
    assertThat(captor.getValue().collection()).isEqualTo("admitted");
  }

  @Test
  void succeededCommandRecordsCollectionAndCommand() {
    started(1, "find", "orders");
    succeeded(1, "find", 5);

    MongoCommandSample sample = recorded();
    assertThat(sample.collection()).isEqualTo("orders");
    assertThat(sample.command()).isEqualTo("find");
    assertThat(sample.outcome()).isEqualTo("ok");
    assertThat(sample.durationMs()).isEqualTo(5.0);
  }

  @Test
  void failedCommandRecordsOutcomeError() {
    started(2, "insert", "users");
    listener.commandFailed(
        new CommandFailedEvent(
            null, 1L, 2, null, "wh", "insert", TimeUnit.MILLISECONDS.toNanos(3), new Exception()));

    MongoCommandSample sample = recorded();
    assertThat(sample.collection()).isEqualTo("users");
    assertThat(sample.outcome()).isEqualTo("error");
  }

  @Test
  void slowCommandIsTaggedSlow() {
    started(3, "aggregate", "orders");
    succeeded(3, "aggregate", 250);

    assertThat(recorded().outcome()).isEqualTo("slow");
  }

  @Test
  void fastCommandUnderThresholdIsOk() {
    started(4, "update", "orders");
    succeeded(4, "update", 40);

    assertThat(recorded().outcome()).isEqualTo("ok");
  }

  @Test
  void adminCommandsWithoutCollectionAreIgnored() {
    int id = 10;
    for (String command : new String[] {"hello", "ping", "isMaster"}) {
      listener.commandStarted(
          new CommandStartedEvent(
              null,
              1L,
              id,
              null,
              "admin",
              command,
              new BsonDocument(command, new org.bson.BsonInt32(1))));
      succeeded(id, command, 1);
      id++;
    }

    verify(port, never()).recordMongoCommand(any());
  }

  @Test
  void commandWithNonStringCollectionIsIgnored() {
    listener.commandStarted(
        new CommandStartedEvent(
            null,
            1L,
            5,
            null,
            "wh",
            "count",
            new BsonDocument("count", new org.bson.BsonInt32(1))));
    succeeded(5, "count", 1);

    verify(port, never()).recordMongoCommand(any());
  }

  @Test
  void finishWithoutStartIsIgnored() {
    succeeded(99, "find", 1);

    verify(port, never()).recordMongoCommand(any());
  }

  @Test
  void poolCountersTrackCheckoutAndCheckin() {
    assertThat(listener.poolStats()).isEqualTo(new MongoMetricsListener.PoolStats(0, 0));

    listener.connectionCreated(mock(ConnectionCreatedEvent.class));
    listener.connectionCreated(mock(ConnectionCreatedEvent.class));
    assertThat(listener.poolStats()).isEqualTo(new MongoMetricsListener.PoolStats(0, 2));

    ConnectionCheckedOutEvent out = mock(ConnectionCheckedOutEvent.class);
    when(out.getElapsedTime(TimeUnit.NANOSECONDS)).thenReturn(TimeUnit.MILLISECONDS.toNanos(7));
    listener.connectionCheckedOut(out);
    assertThat(listener.poolStats()).isEqualTo(new MongoMetricsListener.PoolStats(1, 1));
    verify(port).recordMongoCheckout(7.0);

    listener.connectionCheckedIn(mock(ConnectionCheckedInEvent.class));
    assertThat(listener.poolStats()).isEqualTo(new MongoMetricsListener.PoolStats(0, 2));

    listener.connectionClosed(mock(ConnectionClosedEvent.class));
    assertThat(listener.poolStats()).isEqualTo(new MongoMetricsListener.PoolStats(0, 1));
  }

  @Test
  void poolStatsNeverGoNegative() {
    listener.connectionCheckedIn(mock(ConnectionCheckedInEvent.class));
    listener.connectionClosed(mock(ConnectionClosedEvent.class));

    assertThat(listener.poolStats()).isEqualTo(new MongoMetricsListener.PoolStats(0, 0));
  }

  @Test
  void listenerNeverThrows() {
    doThrow(new IllegalStateException("down")).when(port).recordMongoCommand(any());
    doThrow(new IllegalStateException("down"))
        .when(port)
        .recordMongoCheckout(org.mockito.ArgumentMatchers.anyDouble());
    started(6, "find", "orders");
    ConnectionCheckedOutEvent out = mock(ConnectionCheckedOutEvent.class);

    assertThatCode(() -> succeeded(6, "find", 1)).doesNotThrowAnyException();
    assertThatCode(() -> listener.connectionCheckedOut(out)).doesNotThrowAnyException();
    assertThatCode(() -> listener.commandStarted(null)).doesNotThrowAnyException();
    assertThatCode(() -> listener.commandSucceeded(null)).doesNotThrowAnyException();
    assertThatCode(() -> listener.commandFailed(null)).doesNotThrowAnyException();
  }
}
