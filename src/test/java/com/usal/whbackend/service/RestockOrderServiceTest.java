package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.restock.order.CreateRestockOrderRequest;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.RestockOrderRepository;
import com.usal.whbackend.service.exception.RestockOrderNotFoundException;
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
    var request = new CreateRestockOrderRequest("ghost", 50, "Distribuidora XYZ");
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
    var request = new CreateRestockOrderRequest("p1", 50, "Distribuidora XYZ");
    assertThrows(
        ResponseStatusException.class,
        () -> restockOrderService.createRestockOrder(request, "user-1"));
  }

  @Test
  void createRestockOrder_valid_savesWithRequestingUser() {
    when(productRepository.findById("p1")).thenReturn(Optional.of(activeProduct("p1")));
    when(restockOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var request = new CreateRestockOrderRequest("p1", 50, "Distribuidora XYZ");
    RestockOrder result = restockOrderService.createRestockOrder(request, "user-1");

    assertEquals("p1", result.getProductId());
    assertEquals(50, result.getQuantityRequested());
    assertEquals("Distribuidora XYZ", result.getSupplier());
    assertEquals("user-1", result.getRequestedByUserId());
    assertNotNull(result.getCreatedAt());
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
                    null, null, "not-a-date", null, PageRequest.of(0, 10)));
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
            PageRequest.of(0, 10));

    assertEquals(1, page.getTotalElements());
  }

  @Test
  void getRestockOrders_noFilters_stillReturnsPage() {
    when(mongoTemplate.count(any(Query.class), eq(RestockOrder.class))).thenReturn(0L);
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class))).thenReturn(List.of());

    var page = restockOrderService.getRestockOrders(null, null, null, null, PageRequest.of(0, 10));

    assertEquals(0, page.getTotalElements());
  }

  @Test
  void getRestockOrders_onlyFromDate_appliesGteOnly() {
    when(mongoTemplate.count(any(Query.class), eq(RestockOrder.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class)))
        .thenReturn(List.of(new RestockOrder()));

    var page =
        restockOrderService.getRestockOrders(
            null, null, "2026-01-01T00:00:00Z", null, PageRequest.of(0, 10));

    assertEquals(1, page.getTotalElements());
  }

  @Test
  void getRestockOrders_onlyToDate_appliesLteOnly() {
    when(mongoTemplate.count(any(Query.class), eq(RestockOrder.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class)))
        .thenReturn(List.of(new RestockOrder()));

    var page =
        restockOrderService.getRestockOrders(
            null, null, null, "2026-12-31T00:00:00Z", PageRequest.of(0, 10));

    assertEquals(1, page.getTotalElements());
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
