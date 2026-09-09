package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StockDrainServiceTest {

  private PositionRepository positionRepository;
  private ProductRepository productRepository;
  private StockEventPublisher publisher;
  private StockDrainService service;

  @BeforeEach
  void setUp() {
    positionRepository = mock(PositionRepository.class);
    productRepository = mock(ProductRepository.class);
    publisher = mock(StockEventPublisher.class);
    service = new StockDrainService(positionRepository, productRepository, List.of(publisher));
  }

  private Position position(String id, int stock, int minutesOld) {
    Position p = new Position();
    p.setId(id);
    p.setProductId("prod-1");
    p.setCurrentStock(stock);
    p.setActive(true);
    p.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(60L * minutesOld));
    return p;
  }

  private OrderItem item(String productId, int quantity) {
    OrderItem i = new OrderItem();
    i.setProductId(productId);
    i.setQuantity(quantity);
    return i;
  }

  private Product product(int minimumStock) {
    Product p = new Product();
    p.setId("prod-1");
    p.setSku("SKU-1");
    p.setName("Widget");
    p.setMinimumStock(minimumStock);
    return p;
  }

  @Test
  void drain_nullItems_isANoOp() {
    assertThatCode(() -> service.drain(null)).doesNotThrowAnyException();
    verifyNoInteractions(positionRepository, productRepository, publisher);
  }

  @Test
  void drain_emptyItems_isANoOp() {
    service.drain(List.of());
    verifyNoInteractions(positionRepository, productRepository);
  }

  @Test
  void drain_consumesOldestPositionsFirstAndStopsWhenSatisfied() {
    Position oldest = position("p1", 5, 0);
    Position newer = position("p2", 10, 10);
    Position untouched = position("p3", 10, 20);
    when(positionRepository
            .findByProductIdAndIsActiveTrueAndCurrentStockGreaterThanOrderByCreatedAtAsc(
                "prod-1", 0))
        .thenReturn(List.of(oldest, newer, untouched));
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("prod-1")))
        .thenReturn(List.of(oldest, newer, untouched));
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(product(0)));

    service.drain(List.of(item("prod-1", 8)));

    assertThat(oldest.getCurrentStock()).isZero();
    assertThat(newer.getCurrentStock()).isEqualTo(7);
    assertThat(untouched.getCurrentStock()).isEqualTo(10);
    verify(positionRepository).save(oldest);
    verify(positionRepository).save(newer);
    verify(positionRepository, never()).save(untouched);
  }

  @Test
  void drain_insufficientStock_drainsWhatIsAvailable() {
    Position only = position("p1", 3, 0);
    when(positionRepository
            .findByProductIdAndIsActiveTrueAndCurrentStockGreaterThanOrderByCreatedAtAsc(
                "prod-1", 0))
        .thenReturn(List.of(only));
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("prod-1")))
        .thenReturn(List.of(only));
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(product(0)));

    service.drain(List.of(item("prod-1", 10)));

    assertThat(only.getCurrentStock()).isZero();
  }

  @Test
  void drain_belowMinimumStock_broadcastsAlertToEveryPublisher() {
    StockEventPublisher second = mock(StockEventPublisher.class);
    service =
        new StockDrainService(positionRepository, productRepository, List.of(publisher, second));

    Position p = position("p1", 10, 0);
    when(positionRepository
            .findByProductIdAndIsActiveTrueAndCurrentStockGreaterThanOrderByCreatedAtAsc(
                "prod-1", 0))
        .thenReturn(List.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("prod-1")))
        .thenReturn(List.of(p));
    Product product = product(5);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));

    service.drain(List.of(item("prod-1", 8)));

    verify(publisher).broadcastStockAlert(product, 2);
    verify(second).broadcastStockAlert(product, 2);
  }

  @Test
  void drain_aboveMinimumStock_doesNotBroadcast() {
    Position p = position("p1", 10, 0);
    when(positionRepository
            .findByProductIdAndIsActiveTrueAndCurrentStockGreaterThanOrderByCreatedAtAsc(
                "prod-1", 0))
        .thenReturn(List.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("prod-1")))
        .thenReturn(List.of(p));
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(product(1)));

    service.drain(List.of(item("prod-1", 2)));

    verify(publisher, never()).broadcastStockAlert(any(), anyInt());
  }

  @Test
  void drain_unknownProduct_skipsTheAlertCheck() {
    when(positionRepository
            .findByProductIdAndIsActiveTrueAndCurrentStockGreaterThanOrderByCreatedAtAsc(
                eq("ghost"), anyInt()))
        .thenReturn(List.of());
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("ghost")))
        .thenReturn(List.of());
    when(productRepository.findById("ghost")).thenReturn(Optional.empty());

    service.drain(List.of(item("ghost", 1)));

    verify(publisher, never()).broadcastStockAlert(any(), anyInt());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void drain_handlesMultipleItemsIndependently() {
    Position a = position("p1", 4, 0);
    Position b = position("p2", 4, 0);
    b.setProductId("prod-2");
    when(positionRepository
            .findByProductIdAndIsActiveTrueAndCurrentStockGreaterThanOrderByCreatedAtAsc(
                "prod-1", 0))
        .thenReturn(List.of(a));
    when(positionRepository
            .findByProductIdAndIsActiveTrueAndCurrentStockGreaterThanOrderByCreatedAtAsc(
                "prod-2", 0))
        .thenReturn(List.of(b));
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    when(productRepository.findById(anyString())).thenReturn(Optional.empty());

    service.drain(List.of(item("prod-1", 1), item("prod-2", 2)));

    assertThat(a.getCurrentStock()).isEqualTo(3);
    assertThat(b.getCurrentStock()).isEqualTo(2);
  }

  @Test
  void constructor_defensivelyCopiesThePublisherList() {
    java.util.List<StockEventPublisher> mutable = new java.util.ArrayList<>();
    mutable.add(publisher);
    StockDrainService s = new StockDrainService(positionRepository, productRepository, mutable);
    mutable.clear();

    Position p = position("p1", 10, 0);
    when(positionRepository
            .findByProductIdAndIsActiveTrueAndCurrentStockGreaterThanOrderByCreatedAtAsc(
                "prod-1", 0))
        .thenReturn(List.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("prod-1")))
        .thenReturn(List.of(p));
    Product product = product(100);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));

    s.drain(List.of(item("prod-1", 1)));

    verify(publisher).broadcastStockAlert(product, 9);
  }
}
