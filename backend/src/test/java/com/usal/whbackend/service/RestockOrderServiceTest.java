package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.restock.order.CreateRestockOrderRequest;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.domain.RestockOrderStatus;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.RestockOrderRepository;
import com.usal.whbackend.service.exception.RestockOrderNotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RestockOrderServiceTest {

  @Mock RestockOrderRepository restockOrderRepository;
  @Mock ProductRepository productRepository;
  @Mock MongoTemplate mongoTemplate;
  @InjectMocks RestockOrderService restockOrderService;

  private Product activeProduct(String id) {
    Product p = new Product();
    p.setId(id);
    p.setActive(true);
    return p;
  }

  @Test
  void createRestockOrder_productNotFound_throwsNotFound() {
    when(productRepository.findById("ghost")).thenReturn(Optional.empty());
    var request = new CreateRestockOrderRequest("ghost", 50, "Distribuidora XYZ", null);
    var ex =
        assertThrows(
            ResponseStatusException.class,
            () -> restockOrderService.createRestockOrder(request, "user-1"));
    assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    assertEquals("PRODUCT_NOT_FOUND", ex.getReason());
  }

  @Test
  void createRestockOrder_productInactive_throwsNotFound() {
    Product inactive = activeProduct("p1");
    inactive.setActive(false);
    when(productRepository.findById("p1")).thenReturn(Optional.of(inactive));
    var request = new CreateRestockOrderRequest("p1", 50, "Distribuidora XYZ", null);
    assertThrows(
        ResponseStatusException.class,
        () -> restockOrderService.createRestockOrder(request, "user-1"));
  }

  @Test
  void createRestockOrder_valid_savesWithRequestingUserAndDefaultsToPending() {
    when(productRepository.findById("p1")).thenReturn(Optional.of(activeProduct("p1")));
    when(restockOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var request = new CreateRestockOrderRequest("p1", 50, "Distribuidora XYZ", null);
    RestockOrder result = restockOrderService.createRestockOrder(request, "user-1");

    assertEquals("p1", result.getProductId());
    assertEquals(50, result.getQuantityRequested());
    assertEquals("Distribuidora XYZ", result.getSupplier());
    assertEquals("user-1", result.getRequestedByUserId());
    assertEquals(RestockOrderStatus.PENDING, result.getStatus());
    assertNull(result.getExpectedDeliveryDate());
    assertNotNull(result.getCreatedAt());
  }

  @Test
  void createRestockOrder_withExpectedDeliveryDate_storesIt() {
    when(productRepository.findById("p1")).thenReturn(Optional.of(activeProduct("p1")));
    when(restockOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Instant expected = Instant.now().plus(7, ChronoUnit.DAYS);
    var request = new CreateRestockOrderRequest("p1", 50, "Distribuidora XYZ", expected);
    RestockOrder result = restockOrderService.createRestockOrder(request, "user-1");

    assertEquals(expected, result.getExpectedDeliveryDate());
  }

  @Test
  void createRestockOrder_expectedDateInPast_throwsBadRequest() {
    when(productRepository.findById("p1")).thenReturn(Optional.of(activeProduct("p1")));

    Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
    var request = new CreateRestockOrderRequest("p1", 50, "Distribuidora XYZ", past);
    var ex =
        assertThrows(
            ResponseStatusException.class,
            () -> restockOrderService.createRestockOrder(request, "user-1"));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    assertEquals("INVALID_EXPECTED_DELIVERY_DATE", ex.getReason());
    verify(restockOrderRepository, never()).save(any());
  }

  @Test
  void getRestockOrder_notFound_throwsRestockOrderNotFoundException() {
    when(restockOrderRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(
        RestockOrderNotFoundException.class, () -> restockOrderService.getRestockOrder("bad"));
  }

  @Test
  void getRestockOrder_found_returnsOrder() {
    RestockOrder order = new RestockOrder();
    order.setId("rso-1");
    when(restockOrderRepository.findById("rso-1")).thenReturn(Optional.of(order));
    assertEquals("rso-1", restockOrderService.getRestockOrder("rso-1").getId());
  }

  @Test
  void cancelRestockOrder_notFound_throwsRestockOrderNotFoundException() {
    when(restockOrderRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(
        RestockOrderNotFoundException.class,
        () -> restockOrderService.cancelRestockOrder("bad", "no supplier", "user-1"));
  }

  @Test
  void cancelRestockOrder_alreadyCancelled_throwsConflict() {
    RestockOrder cancelled = new RestockOrder();
    cancelled.setId("rso-1");
    cancelled.setStatus(RestockOrderStatus.CANCELLED);
    when(restockOrderRepository.findById("rso-1")).thenReturn(Optional.of(cancelled));

    var ex =
        assertThrows(
            ResponseStatusException.class,
            () -> restockOrderService.cancelRestockOrder("rso-1", null, "user-1"));
    assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    assertEquals("RESTOCK_ORDER_ALREADY_CANCELLED", ex.getReason());
    verify(restockOrderRepository, never()).save(any());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  void cancelRestockOrder_hasReceptions_throwsConflict() {
    RestockOrder pending = new RestockOrder();
    pending.setId("rso-1");
    pending.setStatus(RestockOrderStatus.PENDING);
    when(restockOrderRepository.findById("rso-1")).thenReturn(Optional.of(pending));

    AggregationResults<RestockOrderService.QuantitySum> results = mock(AggregationResults.class);
    when(results.getUniqueMappedResult()).thenReturn(new RestockOrderService.QuantitySum(10));
    when(mongoTemplate.aggregate(
            any(Aggregation.class), eq("receptions"), eq(RestockOrderService.QuantitySum.class)))
        .thenReturn((AggregationResults) results);

    var ex =
        assertThrows(
            ResponseStatusException.class,
            () -> restockOrderService.cancelRestockOrder("rso-1", null, "user-1"));
    assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    assertEquals("RESTOCK_ORDER_HAS_RECEPTIONS", ex.getReason());
    verify(restockOrderRepository, never()).save(any());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  void cancelRestockOrder_valid_marksCancelledAndSaves() {
    RestockOrder pending = new RestockOrder();
    pending.setId("rso-1");
    pending.setStatus(RestockOrderStatus.PENDING);
    when(restockOrderRepository.findById("rso-1")).thenReturn(Optional.of(pending));

    AggregationResults<RestockOrderService.QuantitySum> empty = mock(AggregationResults.class);
    when(empty.getUniqueMappedResult()).thenReturn(null);
    when(mongoTemplate.aggregate(
            any(Aggregation.class), eq("receptions"), eq(RestockOrderService.QuantitySum.class)))
        .thenReturn((AggregationResults) empty);
    when(restockOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RestockOrder result =
        restockOrderService.cancelRestockOrder("rso-1", "supplier closed", "user-1");

    assertEquals(RestockOrderStatus.CANCELLED, result.getStatus());
    assertEquals("supplier closed", result.getCancellationReason());
    assertEquals("user-1", result.getCancelledByUserId());
    assertNotNull(result.getCancelledAt());
    assertTrue(result.isCancelled());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  void cancelRestockOrder_withoutReason_savesNullReason() {
    RestockOrder pending = new RestockOrder();
    pending.setId("rso-1");
    pending.setStatus(RestockOrderStatus.PENDING);
    when(restockOrderRepository.findById("rso-1")).thenReturn(Optional.of(pending));

    AggregationResults<RestockOrderService.QuantitySum> empty = mock(AggregationResults.class);
    when(empty.getUniqueMappedResult()).thenReturn(null);
    when(mongoTemplate.aggregate(
            any(Aggregation.class), eq("receptions"), eq(RestockOrderService.QuantitySum.class)))
        .thenReturn((AggregationResults) empty);
    when(restockOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RestockOrder result = restockOrderService.cancelRestockOrder("rso-1", null, "user-1");

    assertNull(result.getCancellationReason());
    assertEquals(RestockOrderStatus.CANCELLED, result.getStatus());
  }

  @Test
  void getRestockOrder_ofItsSupplier_isFoundByTheProvider() {
    RestockOrder order = new RestockOrder();
    order.setId("rso-1");
    order.setSupplier("Distribuidora XYZ");
    when(restockOrderRepository.findById("rso-1")).thenReturn(Optional.of(order));
    assertEquals(
        "rso-1", restockOrderService.getRestockOrder("rso-1", "Distribuidora XYZ").getId());
  }

  @Test
  void getRestockOrder_ofAnotherSupplier_looksMissingToTheProvider() {
    RestockOrder order = new RestockOrder();
    order.setId("rso-1");
    order.setSupplier("Distribuidora XYZ");
    when(restockOrderRepository.findById("rso-1")).thenReturn(Optional.of(order));
    assertThrows(
        RestockOrderNotFoundException.class,
        () -> restockOrderService.getRestockOrder("rso-1", "Otro Proveedor"));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  void computeReceivedSoFar_noReceptions_returnsZero() {
    AggregationResults<Object> empty = mock(AggregationResults.class);
    when(empty.getUniqueMappedResult()).thenReturn(null);
    when(mongoTemplate.aggregate(any(Aggregation.class), eq("receptions"), any(Class.class)))
        .thenReturn((AggregationResults) empty);

    assertEquals(0, restockOrderService.computeReceivedSoFar("rso-1"));
  }

  @Test
  void getRestockOrders_invalidFromDate_throwsBadRequest() {
    var ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                restockOrderService.getRestockOrders(
                    null, null, "not-a-date", null, false, PageRequest.of(0, 10)));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    assertEquals("INVALID_DATE_FORMAT", ex.getReason());
  }

  @Test
  void getRestockOrders_appliesFiltersAndPagination() {
    when(mongoTemplate.count(any(Query.class), eq(RestockOrder.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class)))
        .thenReturn(List.of(new RestockOrder()));

    var page =
        restockOrderService.getRestockOrders(
            "p1",
            "Distribuidora XYZ",
            "2026-01-01T00:00:00Z",
            "2026-12-31T00:00:00Z",
            false,
            PageRequest.of(0, 10));

    assertEquals(1, page.getTotalElements());
  }

  @Test
  void getRestockOrders_noFilters_stillReturnsPage() {
    when(mongoTemplate.count(any(Query.class), eq(RestockOrder.class))).thenReturn(0L);
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class))).thenReturn(List.of());

    var page =
        restockOrderService.getRestockOrders(null, null, null, null, false, PageRequest.of(0, 10));

    assertEquals(0, page.getTotalElements());
  }

  @Test
  void getRestockOrders_onlyFromDate_appliesGteOnly() {
    when(mongoTemplate.count(any(Query.class), eq(RestockOrder.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class)))
        .thenReturn(List.of(new RestockOrder()));

    var page =
        restockOrderService.getRestockOrders(
            null, null, "2026-01-01T00:00:00Z", null, false, PageRequest.of(0, 10));

    assertEquals(1, page.getTotalElements());
  }

  @Test
  void getRestockOrders_onlyToDate_appliesLteOnly() {
    when(mongoTemplate.count(any(Query.class), eq(RestockOrder.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class)))
        .thenReturn(List.of(new RestockOrder()));

    var page =
        restockOrderService.getRestockOrders(
            null, null, null, "2026-12-31T00:00:00Z", false, PageRequest.of(0, 10));

    assertEquals(1, page.getTotalElements());
  }

  @Test
  void getRestockOrders_includeCancelledFalse_filtersOutCancelled() {
    when(mongoTemplate.count(any(Query.class), eq(RestockOrder.class))).thenReturn(0L);
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class))).thenReturn(List.of());

    restockOrderService.getRestockOrders(null, null, null, null, false, PageRequest.of(0, 10));

    org.mockito.ArgumentCaptor<Query> captor = org.mockito.ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(captor.capture(), eq(RestockOrder.class));
    // Inspect the top-level BSON keys only: calling toJson() on the full Document would need a
    // codec for RestockOrderStatus (not registered in a plain BSON codec registry) and throw.
    assertTrue(
        captor.getValue().getQueryObject().containsKey("$or"),
        "expected a status $or filter when includeCancelled=false");
  }

  @Test
  void getRestockOrders_includeCancelledTrue_noStatusFilter() {
    when(mongoTemplate.count(any(Query.class), eq(RestockOrder.class))).thenReturn(0L);
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class))).thenReturn(List.of());

    restockOrderService.getRestockOrders(null, null, null, null, true, PageRequest.of(0, 10));

    org.mockito.ArgumentCaptor<Query> captor = org.mockito.ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(captor.capture(), eq(RestockOrder.class));
    org.bson.Document query = captor.getValue().getQueryObject();
    assertFalse(
        query.containsKey("$or") || query.containsKey("status"),
        "expected no status filter when includeCancelled=true");
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  void computeReceivedSoFar_withReceptions_returnsSum() {
    AggregationResults<RestockOrderService.QuantitySum> results = mock(AggregationResults.class);
    when(results.getUniqueMappedResult()).thenReturn(new RestockOrderService.QuantitySum(48));
    when(mongoTemplate.aggregate(
            any(Aggregation.class), eq("receptions"), eq(RestockOrderService.QuantitySum.class)))
        .thenReturn((AggregationResults) results);

    assertEquals(48, restockOrderService.computeReceivedSoFar("rso-1"));
  }
}
