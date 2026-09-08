package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.restock.reception.CreateReceptionRequest;
import com.usal.whbackend.api.restock.reception.CreateReceptionRequest.AssignmentRequest;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.ReceptionRepository;
import com.usal.whbackend.repository.RestockOrderRepository;
import com.usal.whbackend.service.exception.ReceptionNotFoundException;
import com.usal.whbackend.service.exception.StockExceedsCapacityException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ReceptionServiceTest {

  @Mock ReceptionRepository receptionRepository;
  @Mock RestockOrderRepository restockOrderRepository;
  @Mock ProductRepository productRepository;
  @Mock PositionService positionService;
  @Mock MongoTemplate mongoTemplate;
  @InjectMocks ReceptionService receptionService;

  private Product activeProduct(String id) {
    Product p = new Product();
    p.setId(id);
    p.setActive(true);
    return p;
  }

  private CreateReceptionRequest request(
      String restockOrderId, List<AssignmentRequest> assignments) {
    int total = assignments.stream().mapToInt(AssignmentRequest::quantity).sum();
    return new CreateReceptionRequest(
        restockOrderId, "p1", total, StockSize.PALLET, "Distribuidora XYZ", assignments);
  }

  @Test
  void createReception_productNotFound_throwsNotFound() {
    when(productRepository.findById("p1")).thenReturn(Optional.empty());
    var req = request(null, List.of(new AssignmentRequest("pos-1", 10)));
    var ex =
        assertThrows(
            ResponseStatusException.class, () -> receptionService.createReception(req, "user-1"));
    assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    assertEquals("PRODUCT_NOT_FOUND", ex.getReason());
    verifyNoInteractions(positionService);
  }

  @Test
  void createReception_productInactive_throwsNotFound() {
    Product inactive = activeProduct("p1");
    inactive.setActive(false);
    when(productRepository.findById("p1")).thenReturn(Optional.of(inactive));
    var req = request(null, List.of(new AssignmentRequest("pos-1", 10)));
    assertThrows(
        ResponseStatusException.class, () -> receptionService.createReception(req, "user-1"));
  }

  @Test
  void createReception_assignmentSumMismatch_throwsBadRequest() {
    when(productRepository.findById("p1")).thenReturn(Optional.of(activeProduct("p1")));
    var req =
        new CreateReceptionRequest(
            null,
            "p1",
            48,
            StockSize.PALLET,
            "Distribuidora XYZ",
            List.of(new AssignmentRequest("pos-1", 30), new AssignmentRequest("pos-2", 10)));

    var ex =
        assertThrows(
            ResponseStatusException.class, () -> receptionService.createReception(req, "user-1"));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    assertEquals("ASSIGNMENT_QUANTITY_MISMATCH", ex.getReason());
    verifyNoInteractions(positionService);
  }

  @Test
  void createReception_restockOrderNotFound_throwsNotFound() {
    when(productRepository.findById("p1")).thenReturn(Optional.of(activeProduct("p1")));
    when(restockOrderRepository.findById("rso-1")).thenReturn(Optional.empty());
    var req = request("rso-1", List.of(new AssignmentRequest("pos-1", 10)));

    var ex =
        assertThrows(
            ResponseStatusException.class, () -> receptionService.createReception(req, "user-1"));
    assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    assertEquals("RESTOCK_ORDER_NOT_FOUND", ex.getReason());
  }

  @Test
  void createReception_restockOrderProductMismatch_throwsBadRequest() {
    when(productRepository.findById("p1")).thenReturn(Optional.of(activeProduct("p1")));
    RestockOrder order = new RestockOrder();
    order.setId("rso-1");
    order.setProductId("other-product");
    when(restockOrderRepository.findById("rso-1")).thenReturn(Optional.of(order));
    var req = request("rso-1", List.of(new AssignmentRequest("pos-1", 10)));

    var ex =
        assertThrows(
            ResponseStatusException.class, () -> receptionService.createReception(req, "user-1"));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    assertEquals("RESTOCK_ORDER_PRODUCT_MISMATCH", ex.getReason());
    verifyNoInteractions(positionService);
  }

  @Test
  void createReception_valid_noRestockOrder_increasesEachAssignmentAndSaves() {
    when(productRepository.findById("p1")).thenReturn(Optional.of(activeProduct("p1")));
    when(positionService.increaseStock(anyString(), eq("p1"), anyInt())).thenReturn(new Position());
    when(receptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var req =
        request(
            null, List.of(new AssignmentRequest("pos-1", 30), new AssignmentRequest("pos-2", 18)));
    Reception result = receptionService.createReception(req, "user-1");

    verify(positionService).increaseStock("pos-1", "p1", 30);
    verify(positionService).increaseStock("pos-2", "p1", 18);
    assertEquals("p1", result.getProductId());
    assertEquals(48, result.getQuantityReceived());
    assertEquals(StockSize.PALLET, result.getDeliveryUnit());
    assertEquals("user-1", result.getReceivedByUserId());
    assertEquals(2, result.getAssignments().size());
    assertNull(result.getRestockOrderId());
  }

  @Test
  void createReception_valid_withMatchingRestockOrder_succeeds() {
    when(productRepository.findById("p1")).thenReturn(Optional.of(activeProduct("p1")));
    RestockOrder order = new RestockOrder();
    order.setId("rso-1");
    order.setProductId("p1");
    when(restockOrderRepository.findById("rso-1")).thenReturn(Optional.of(order));
    when(positionService.increaseStock(anyString(), eq("p1"), anyInt())).thenReturn(new Position());
    when(receptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var req = request("rso-1", List.of(new AssignmentRequest("pos-1", 48)));
    Reception result = receptionService.createReception(req, "user-1");

    assertEquals("rso-1", result.getRestockOrderId());
  }

  @Test
  void createReception_positionIncreaseFails_stopsWithoutSavingReception() {
    when(productRepository.findById("p1")).thenReturn(Optional.of(activeProduct("p1")));
    when(positionService.increaseStock("pos-1", "p1", 30)).thenReturn(new Position());
    when(positionService.increaseStock("pos-2", "p1", 18))
        .thenThrow(new StockExceedsCapacityException(18, 10));

    var req =
        request(
            null, List.of(new AssignmentRequest("pos-1", 30), new AssignmentRequest("pos-2", 18)));

    assertThrows(
        StockExceedsCapacityException.class, () -> receptionService.createReception(req, "user-1"));
    verifyNoInteractions(receptionRepository);
  }

  @Test
  void getReception_notFound_throwsReceptionNotFoundException() {
    when(receptionRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(ReceptionNotFoundException.class, () -> receptionService.getReception("bad"));
  }

  @Test
  void getReception_found_returnsReception() {
    Reception r = new Reception();
    r.setId("rcp-1");
    when(receptionRepository.findById("rcp-1")).thenReturn(Optional.of(r));
    assertEquals("rcp-1", receptionService.getReception("rcp-1").getId());
  }

  @Test
  void getReceptions_invalidToDate_throwsBadRequest() {
    var ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                receptionService.getReceptions(
                    null, null, null, "not-a-date", PageRequest.of(0, 10)));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    assertEquals("INVALID_DATE_FORMAT", ex.getReason());
  }

  @Test
  void getReceptions_appliesFiltersAndPagination() {
    when(mongoTemplate.count(any(Query.class), eq(Reception.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Reception.class)))
        .thenReturn(List.of(new Reception()));

    var page =
        receptionService.getReceptions(
            "p1", "rso-1", "2026-01-01T00:00:00Z", null, PageRequest.of(0, 10));

    assertEquals(1, page.getTotalElements());
  }

  @Test
  void getReceptions_noFilters_stillReturnsPage() {
    when(mongoTemplate.count(any(Query.class), eq(Reception.class))).thenReturn(0L);
    when(mongoTemplate.find(any(Query.class), eq(Reception.class))).thenReturn(List.of());

    var page = receptionService.getReceptions(null, null, null, null, PageRequest.of(0, 10));

    assertEquals(0, page.getTotalElements());
  }

  @Test
  void getReceptions_onlyToDate_appliesLteOnly() {
    when(mongoTemplate.count(any(Query.class), eq(Reception.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Reception.class)))
        .thenReturn(List.of(new Reception()));

    var page =
        receptionService.getReceptions(
            null, null, null, "2026-12-31T00:00:00Z", PageRequest.of(0, 10));

    assertEquals(1, page.getTotalElements());
  }
}
