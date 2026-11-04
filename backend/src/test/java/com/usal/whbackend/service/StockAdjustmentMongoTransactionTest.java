package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.api.stock.StockAdjustmentController;
import com.usal.whbackend.api.warehouse.position.PositionController;
import com.usal.whbackend.api.warehouse.position.UpdatePositionRequest;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.StockAdjustment;
import com.usal.whbackend.domain.StockAdjustmentType;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.StockAdjustmentRepository;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.telemetry.NoOpTelemetryAdapter;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the real services, proxies and {@link MongoTransactionManager} against a real MongoDB
 * replica set (the version production runs), because only that proves the stock, the history entry
 * and the order cancellations commit or roll back together. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class StockAdjustmentMongoTransactionTest {

  private static MongoDBContainer mongo;
  private static MongoClient client;
  private static AnnotationConfigApplicationContext context;

  private static final KafkaTemplate<String, String> KAFKA = mockKafka();

  /** Delegates to the Mongo-backed history repository; tests make its {@code save} fail. */
  private static StockAdjustmentRepository historySpy;

  private static MongoTemplate template;

  @SuppressWarnings("unchecked")
  private static KafkaTemplate<String, String> mockKafka() {
    return mock(KafkaTemplate.class);
  }

  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean
    MongoDatabaseFactory dbFactory() {
      return new SimpleMongoClientDatabaseFactory(client, "wh_test");
    }

    @Bean
    MongoTemplate mongoTemplate(MongoDatabaseFactory f) {
      return new MongoTemplate(f);
    }

    @Bean
    PlatformTransactionManager transactionManager(MongoDatabaseFactory f) {
      return new MongoTransactionManager(f);
    }

    @Bean
    MongoRepositoryFactory repositories(MongoTemplate t) {
      return new MongoRepositoryFactory(t);
    }

    @Bean
    PositionRepository positionRepository(MongoRepositoryFactory f) {
      return f.getRepository(PositionRepository.class);
    }

    @Bean
    ProductRepository productRepository(MongoRepositoryFactory f) {
      return f.getRepository(ProductRepository.class);
    }

    @Bean
    LineRepository lineRepository(MongoRepositoryFactory f) {
      return f.getRepository(LineRepository.class);
    }

    @Bean
    ZoneRepository zoneRepository(MongoRepositoryFactory f) {
      return f.getRepository(ZoneRepository.class);
    }

    @Bean
    OrderRepository orderRepository(MongoRepositoryFactory f, MongoTemplate t) {
      return new OrderRepository(
          f.getRepository(OrderMongoRepository.class),
          KAFKA,
          t,
          new ObjectMapper(),
          new NoOpTelemetryAdapter());
    }

    @Bean
    StockAdjustmentRepository stockAdjustmentRepository(MongoRepositoryFactory f) {
      historySpy =
          mock(
              StockAdjustmentRepository.class,
              AdditionalAnswers.delegatesTo(f.getRepository(StockAdjustmentRepository.class)));
      return historySpy;
    }

    @Bean
    StockAdjustmentService stockAdjustmentService(
        PositionRepository positions,
        ProductRepository products,
        OrderRepository orders,
        StockAdjustmentRepository history) {
      return new StockAdjustmentService(positions, products, orders, history, List.of());
    }

    @Bean
    PositionService positionService(
        PositionRepository positions,
        LineRepository lines,
        ZoneRepository zones,
        ProductRepository products,
        MongoTemplate t,
        StockAdjustmentService adjustments) {
      return new PositionService(positions, lines, zones, products, t, adjustments);
    }
  }

  @BeforeAll
  static void startMongo() {
    // mongo:4.4 is what docker-compose runs in production (no AVX on the prod box).
    mongo = new MongoDBContainer("mongo:4.4").withReplicaSet();
    mongo.start();
    client = MongoClients.create(mongo.getConnectionString());
    context = new AnnotationConfigApplicationContext(Config.class);
    template = context.getBean(MongoTemplate.class);
    // Collections must exist before a transaction touches them (Mongo 4.4 cannot create them in
    // one when the first write is a read-then-upsert); production gets them from auto-indexing.
    for (String name :
        List.of("positions", "orders", "products", "stock_adjustments", "stock_guards")) {
      template.getCollection(name).countDocuments();
      if (!template.collectionExists(name)) {
        template.createCollection(name);
      }
    }
  }

  @AfterAll
  static void stopMongo() {
    if (context != null) context.close();
    if (client != null) client.close();
    if (mongo != null) mongo.stop();
  }

  @BeforeEach
  void clean() {
    for (String name :
        List.of("positions", "orders", "products", "stock_adjustments", "stock_guards")) {
      template.getCollection(name).deleteMany(new org.bson.Document());
    }
    reset(KAFKA);
    when(KAFKA.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    reset(historySpy);
  }

  private static Position position(String id, String productId, int stock) {
    Position p = new Position();
    p.setId(id);
    p.setProductId(productId);
    p.setActive(true);
    p.setMaximumCapacity(100);
    p.setCurrentStock(stock);
    return template.save(p);
  }

  private static Order order(String id, OrderStatus status, int minute, int quantity) {
    Order o = new Order();
    o.setId(id);
    o.setStatus(status);
    o.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minute * 60L));
    o.setItems(List.of(new OrderItem("prod-1", "SKU-1", quantity)));
    return template.save(o);
  }

  private static void seedSpecExample() {
    Product product = new Product();
    product.setId("prod-1");
    template.save(product);
    position("pos-1", "prod-1", 14);
    order("in-process", OrderStatus.IN_PROGRESS, 0, 5);
    order("pending-4", OrderStatus.PENDING, 1, 4);
    order("pending-2", OrderStatus.PENDING, 2, 2);
  }

  private static OrderStatus statusOf(String id) {
    return template.findById(id, Order.class).getStatus();
  }

  private static int stockOf(String id) {
    return template.findById(id, Position.class).getCurrentStock();
  }

  private static long historyCount() {
    return template.count(
        new org.springframework.data.mongodb.core.query.Query(), StockAdjustment.class);
  }

  @Test
  void adjust_commitsStockHistoryAndCancellationsTogether_andPublishesAfterTheCommit() {
    seedSpecExample();
    StockAdjustmentService service = context.getBean(StockAdjustmentService.class);

    service.adjust("pos-1", 6, StockAdjustmentType.DAMAGE, "rotas");

    assertThat(stockOf("pos-1")).isEqualTo(8);
    assertThat(statusOf("in-process")).isEqualTo(OrderStatus.IN_PROGRESS);
    assertThat(statusOf("pending-4")).isEqualTo(OrderStatus.CANCELLED);
    assertThat(statusOf("pending-2")).isEqualTo(OrderStatus.PENDING);
    assertThat(template.findAll(StockAdjustment.class))
        .singleElement()
        .satisfies(a -> assertThat(a.cancelledOrderIds()).containsExactly("pending-4"));
    verify(KAFKA, timeout(5000)).send(any(ProducerRecord.class));
  }

  @Test
  void adjust_whenTheHistoryWriteFails_rollsBackStockAndCancellations_andPublishesNothing()
      throws Exception {
    seedSpecExample();
    doThrow(new IllegalStateException("history down")).when(historySpy).save(any());
    StockAdjustmentService service = context.getBean(StockAdjustmentService.class);

    assertThatThrownBy(() -> service.adjust("pos-1", 6, StockAdjustmentType.DAMAGE, "rotas"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(stockOf("pos-1")).isEqualTo(14);
    assertThat(statusOf("pending-4")).isEqualTo(OrderStatus.PENDING);
    assertThat(template.findById("pending-4", Order.class).getCancelReason()).isNull();
    assertThat(historyCount()).isZero();
    Thread.sleep(300);
    verifyNoInteractions(KAFKA);
  }

  @Test
  void patch_whenTheHistoryWriteFails_rollsBackTheWholeUpdate() {
    seedSpecExample();
    doThrow(new IllegalStateException("history down")).when(historySpy).save(any());
    PositionService positions = context.getBean(PositionService.class);
    UpdatePositionRequest request =
        new UpdatePositionRequest(null, null, 8, null, null, null, null, null);

    assertThatThrownBy(() -> positions.updatePosition("pos-1", request))
        .isInstanceOf(IllegalStateException.class);

    assertThat(stockOf("pos-1")).isEqualTo(14);
    assertThat(statusOf("pending-4")).isEqualTo(OrderStatus.PENDING);
    assertThat(historyCount()).isZero();
  }

  @Test
  void patch_unassign_cancelsEveryPendingOrderOfTheProductAtomically() {
    seedSpecExample();
    PositionService positions = context.getBean(PositionService.class);
    UpdatePositionRequest request =
        new UpdatePositionRequest(null, null, null, null, null, true, null, null);

    PositionService.PositionUpdate update = positions.updatePosition("pos-1", request);

    assertThat(update.cancelledOrders())
        .extracting(Order::getId)
        .containsExactlyInAnyOrder("pending-4", "pending-2");
    assertThat(statusOf("in-process")).isEqualTo(OrderStatus.IN_PROGRESS);
    assertThat(template.findAll(StockAdjustment.class))
        .singleElement()
        .satisfies(a -> assertThat(a.type()).isEqualTo(StockAdjustmentType.UNASSIGNED));
  }

  @Test
  void concurrentAdjustmentsOfTheSameProduct_doNotBothReconcileAgainstAStaleView()
      throws Exception {
    Product product = new Product();
    product.setId("prod-1");
    template.save(product);
    position("pos-a", "prod-1", 5);
    position("pos-b", "prod-1", 5);
    order("pending-6", OrderStatus.PENDING, 1, 6);
    StockAdjustmentService service = context.getBean(StockAdjustmentService.class);
    TransactionTemplate tx =
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    CountDownLatch firstHasReconciled = new CountDownLatch(1);
    CountDownLatch secondDone = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // First adjustment: reconciled but not committed yet while the second one runs.
      Future<?> first =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      s -> {
                        service.adjust("pos-a", 2, StockAdjustmentType.MANUAL_DECREASE, null);
                        firstHasReconciled.countDown();
                        try {
                          secondDone.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      }));
      assertThat(firstHasReconciled.await(30, TimeUnit.SECONDS)).isTrue();
      Future<?> second =
          pool.submit(
              () -> {
                try {
                  service.adjust("pos-b", 2, StockAdjustmentType.MANUAL_DECREASE, null);
                } finally {
                  secondDone.countDown();
                }
              });

      assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS))
          .hasRootCauseInstanceOf(com.mongodb.MongoCommandException.class)
          .cause()
          .isInstanceOfSatisfying(
              org.springframework.web.server.ResponseStatusException.class,
              e -> assertThat(e.getReason()).isEqualTo("STOCK_CHANGE_CONFLICT"));
      first.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    // Only the first adjustment landed; the loser changed nothing and can be retried.
    assertThat(stockOf("pos-a")).isEqualTo(3);
    assertThat(stockOf("pos-b")).isEqualTo(5);
    assertThat(historyCount()).isEqualTo(1);
  }

  /**
   * Holds a first change of {@code pos-1} uncommitted (its position write is in flight), runs a
   * second HTTP request against the same position and returns its response; then lets the first one
   * commit. The second request must lose with a clean 409, never a 500.
   */
  private MvcResult requestWhileAnotherChangeOfTheSamePositionIsInFlight(
      MockHttpServletRequestBuilder request) throws Exception {
    StockAdjustmentService service = context.getBean(StockAdjustmentService.class);
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new StockAdjustmentController(service),
                new PositionController(
                    context.getBean(PositionService.class),
                    context.getBean(ProductRepository.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    TransactionTemplate tx =
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    CountDownLatch firstHasWritten = new CountDownLatch(1);
    CountDownLatch secondDone = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      s -> {
                        service.adjust("pos-1", 2, StockAdjustmentType.MANUAL_DECREASE, null);
                        firstHasWritten.countDown();
                        try {
                          secondDone.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      }));
      assertThat(firstHasWritten.await(30, TimeUnit.SECONDS)).isTrue();
      Future<MvcResult> second =
          pool.submit(
              () -> {
                try {
                  return mvc.perform(request).andReturn();
                } finally {
                  secondDone.countDown();
                }
              });
      MvcResult result = second.get(30, TimeUnit.SECONDS);
      first.get(30, TimeUnit.SECONDS);
      return result;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentAdjustmentsOfTheSamePosition_theLoserAnswers409_notA500() throws Exception {
    seedSpecExample();

    MvcResult loser =
        requestWhileAnotherChangeOfTheSamePositionIsInFlight(
            post("/warehouse/positions/pos-1/stock-adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\": 1}"));

    assertThat(loser.getResponse().getStatus()).isEqualTo(409);
    assertThat(loser.getResponse().getContentAsString()).contains("STOCK_CHANGE_CONFLICT");
    // Only the first adjustment landed; the loser changed nothing.
    assertThat(stockOf("pos-1")).isEqualTo(12);
    assertThat(historyCount()).isEqualTo(1);
  }

  @Test
  void concurrentPatchOfTheSamePositionAsAnAdjustment_theLoserAnswers409_notA500()
      throws Exception {
    seedSpecExample();

    MvcResult loser =
        requestWhileAnotherChangeOfTheSamePositionIsInFlight(
            patch("/warehouse/positions/pos-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentStock\": 8}"));

    assertThat(loser.getResponse().getStatus()).isEqualTo(409);
    assertThat(loser.getResponse().getContentAsString()).contains("STOCK_CHANGE_CONFLICT");
    assertThat(stockOf("pos-1")).isEqualTo(12);
    assertThat(historyCount()).isEqualTo(1);
    assertThat(statusOf("pending-4")).isEqualTo(OrderStatus.PENDING);
  }
}
