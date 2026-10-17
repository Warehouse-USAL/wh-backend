package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleIds;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.kafka.VehicleUpdateExecutor;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class OrderStatusServiceTest {

  private static final String VHC_1 = VehicleIds.fromInfra("vhc-1");
  private static final String VHC_9 = VehicleIds.fromInfra("vhc-9");

  private static final String TS = "2026-05-01T10:06:00Z";

  @Mock OrderService orderService;
  @Mock VehicleUpdateExecutor vehicleUpdateExecutor;
  @Mock VehicleEventPublisher vehicleEventPublisher;
  OrderStatusService service;

  @BeforeEach
  void setUp() {
    service = new OrderStatusService(orderService, vehicleUpdateExecutor, vehicleEventPublisher);
  }

  private static Order order(OrderStatus status) {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(status);
    return order;
  }

  private static void assertStatus(HttpStatus expected, ResponseStatusException e, String reason) {
    assertEquals(expected, e.getStatusCode());
    assertEquals(reason, e.getReason());
  }

  @Test
  void inProgress_assignsTheVehicle() {
    Order pending = order(OrderStatus.PENDING);
    Order assigned = order(OrderStatus.IN_PROGRESS);
    when(orderService.getOrder("ord-1")).thenReturn(pending);
    when(orderService.assignVehicle("ord-1", VHC_1)).thenReturn(assigned);

    assertSame(assigned, service.apply("ord-1", "in_progress", "vhc-1", TS));
  }

  @Test
  void inProgress_withoutAVehicle_isABadRequest() {
    when(orderService.getOrder("ord-1")).thenReturn(order(OrderStatus.PENDING));

    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class, () -> service.apply("ord-1", "in_progress", " ", TS));

    assertStatus(HttpStatus.BAD_REQUEST, e, "VEHICLE_ID_REQUIRED");
    verify(orderService, never()).assignVehicle(any(), any());
  }

  @Test
  void completed_completesTheOrderWithTheReportedTimestamp() {
    Order inProgress = order(OrderStatus.IN_PROGRESS);
    Order completed = order(OrderStatus.COMPLETED);
    when(orderService.getOrder("ord-1")).thenReturn(inProgress);
    when(orderService.changeStatus("ord-1", "COMPLETED", Instant.parse(TS))).thenReturn(completed);

    assertSame(completed, service.apply("ord-1", "completed", "vhc-1", TS));
  }

  @Test
  void completed_withoutATimestamp_usesTheCurrentTime() {
    when(orderService.getOrder("ord-1")).thenReturn(order(OrderStatus.IN_PROGRESS));
    when(orderService.changeStatus(eq("ord-1"), eq("COMPLETED"), any()))
        .thenReturn(order(OrderStatus.COMPLETED));
    Instant before = Instant.now();

    service.apply("ord-1", "completed", null, null);

    ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
    verify(orderService).changeStatus(eq("ord-1"), eq("COMPLETED"), captor.capture());
    assertFalse(captor.getValue().isBefore(before));
  }

  /** Stubs the executor to run the requested update against a BUSY vehicle and capture its $set. */
  private void stubRelease(String vehicleId) {
    Vehicle vehicle = new Vehicle();
    vehicle.setId(vehicleId);
    vehicle.setStatus(VehicleStatus.BUSY);
    when(vehicleUpdateExecutor.apply(eq(vehicleId), any()))
        .thenAnswer(
            inv -> {
              Function<Vehicle, Update> builder = inv.getArgument(1);
              Update update = builder.apply(vehicle);
              Vehicle released = new Vehicle();
              released.setId(vehicleId);
              released.setStatus(VehicleStatus.IDLE);
              capturedUpdate = update.getUpdateObject().get("$set", Document.class);
              return Optional.of(new VehicleUpdateExecutor.Result(VehicleStatus.BUSY, released));
            });
  }

  private Document capturedUpdate;

  @Test
  void completed_releasesTheAssignedVehicle() {
    Order inProgress = order(OrderStatus.IN_PROGRESS);
    inProgress.setAssignedVehicleId(VHC_1);
    when(orderService.getOrder("ord-1")).thenReturn(inProgress);
    when(orderService.changeStatus(eq("ord-1"), eq("COMPLETED"), any()))
        .thenReturn(order(OrderStatus.COMPLETED));
    stubRelease(VHC_1);

    service.apply("ord-1", "completed", "vhc-1", TS);

    assertEquals(VehicleStatus.IDLE, capturedUpdate.get("status"));
    assertNull(capturedUpdate.get("currentOrderId"));
    verify(vehicleEventPublisher).broadcastVehicleUpdate(any(Vehicle.class));
  }

  @Test
  void completed_withoutAnAssignedVehicle_releasesTheReportedOne() {
    when(orderService.getOrder("ord-1")).thenReturn(order(OrderStatus.PENDING));
    when(orderService.changeStatus(eq("ord-1"), eq("COMPLETED"), any()))
        .thenReturn(order(OrderStatus.COMPLETED));
    stubRelease(VHC_9);

    service.apply("ord-1", "completed", "vhc-9", TS);

    verify(vehicleUpdateExecutor).apply(eq(VHC_9), any());
  }

  @Test
  void completed_withNoVehicleAtAll_doesNotTouchVehicles() {
    when(orderService.getOrder("ord-1")).thenReturn(order(OrderStatus.IN_PROGRESS));
    when(orderService.changeStatus(eq("ord-1"), eq("COMPLETED"), any()))
        .thenReturn(order(OrderStatus.COMPLETED));

    service.apply("ord-1", "completed", null, TS);

    verifyNoInteractions(vehicleUpdateExecutor, vehicleEventPublisher);
  }

  @Test
  void cancelled_cancelsTheOrderAndReleasesItsVehicle() {
    Order inProgress = order(OrderStatus.IN_PROGRESS);
    inProgress.setAssignedVehicleId(VHC_1);
    when(orderService.getOrder("ord-1")).thenReturn(inProgress);
    when(orderService.changeStatus(eq("ord-1"), eq("CANCELLED"), any()))
        .thenReturn(order(OrderStatus.CANCELLED));
    stubRelease(VHC_1);

    service.apply("ord-1", "cancelled", "vhc-1", TS);

    verify(orderService).changeStatus(eq("ord-1"), eq("CANCELLED"), any());
    assertEquals(VehicleStatus.IDLE, capturedUpdate.get("status"));
  }

  @Test
  void repeatingTheCurrentStatus_isANoOp() {
    Order completed = order(OrderStatus.COMPLETED);
    when(orderService.getOrder("ord-1")).thenReturn(completed);

    // A completed order reported completed again must not drain stock a second time.
    assertSame(completed, service.apply("ord-1", "completed", "vhc-1", TS));

    verify(orderService, never()).changeStatus(any(), any(), any());
    verify(orderService, never()).assignVehicle(any(), any());
    verifyNoInteractions(vehicleUpdateExecutor);
  }

  @Test
  void repeatingInProgress_isANoOp() {
    Order inProgress = order(OrderStatus.IN_PROGRESS);
    when(orderService.getOrder("ord-1")).thenReturn(inProgress);

    assertSame(inProgress, service.apply("ord-1", "in_progress", "vhc-1", TS));

    verify(orderService, never()).assignVehicle(any(), any());
  }

  @Test
  void anythingNewOnACancelledOrder_isAConflict() {
    when(orderService.getOrder("ord-1")).thenReturn(order(OrderStatus.CANCELLED));

    for (String status : new String[] {"completed", "in_progress"}) {
      ResponseStatusException e =
          assertThrows(
              ResponseStatusException.class, () -> service.apply("ord-1", status, "vhc-1", TS));
      assertStatus(HttpStatus.CONFLICT, e, "ORDER_NOT_MODIFIABLE");
    }
    verify(orderService, never()).changeStatus(any(), any(), any());
  }

  @Test
  void anythingNewOnACompletedOrder_isAConflict() {
    when(orderService.getOrder("ord-1")).thenReturn(order(OrderStatus.COMPLETED));

    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class, () -> service.apply("ord-1", "cancelled", null, TS));

    assertStatus(HttpStatus.CONFLICT, e, "ORDER_NOT_MODIFIABLE");
  }

  @Test
  void unknownOrder_isNotFound() {
    when(orderService.getOrder("ghost"))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class, () -> service.apply("ghost", "completed", null, TS));

    assertStatus(HttpStatus.NOT_FOUND, e, "ORDER_NOT_FOUND");
  }

  @Test
  void invalidOrMissingStatus_isABadRequest() {
    for (String status : new String[] {"teleported", "pending", null}) {
      ResponseStatusException e =
          assertThrows(
              ResponseStatusException.class, () -> service.apply("ord-1", status, null, TS));
      assertStatus(HttpStatus.BAD_REQUEST, e, "INVALID_STATUS");
    }
    verifyNoInteractions(orderService);
  }
}
