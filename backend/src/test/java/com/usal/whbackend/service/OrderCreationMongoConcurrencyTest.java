package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.usal.whbackend.api.order.CreateOrderRequest;
import com.usal.whbackend.api.order.CreateOrderRequest.AddressRequest;
import com.usal.whbackend.api.order.CreateOrderRequest.OrderItemRequest;
import com.usal.whbackend.domain.IdempotencyRecord;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.StockAdjustmentType;
import com.usal.whbackend.repository.IdempotencyRecordRepository;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.StockAdjustmentRepository;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.repository.kafka.VehicleUpdateExecutor;
import com.usal.whbackend.service.storage.StorageService;
import com.usal.whbackend.telemetry.NoOpTelemetryAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates orders concurrently against a real MongoDB replica set (the version production runs):
 * only real transactions show whether the stock check and the order commit as one unit. Skipped
 * when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class OrderCreationMongoConcurrencyTest {

  private static MongoDBContainer mongo;
  private static MongoClient client;
  private static AnnotationConfigApplicationContext context;
  private static MongoTemplate template;

  private static final KafkaTemplate<String, String> KAFKA = mockKafka();

  @SuppressWarnings("unchecked")
  private static KafkaTemplate<String, String> mockKafka() {
    return mock(KafkaTemplate.class);
  }

  @Configuration
  @EnableTransactionManagement(proxyTargetClass = true)
  static class Config {
    @Bean
    MongoDatabaseFactory dbFactory() {
      return new SimpleMongoClientDatabaseFactory(client, "wh_test_orders");
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
    IdempotencyRecordRepository idempotencyRecordRepository(MongoRepositoryFactory f) {
      return f.getRepository(IdempotencyRecordRepository.class);
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
    StockAdjustmentService stockAdjustmentService(
        PositionRepository positions,
        ProductRepository products,
        OrderRepository orders,
        MongoRepositoryFactory f) {
      return new StockAdjustmentService(
          positions, products, orders, f.getRepository(StockAdjustmentRepository.class), List.of());
    }

    @Bean
    ProductService productService(
        ProductRepository products,
        PositionRepository positions,
        MongoTemplate t,
        StockAdjustmentService adjustments) {
      return new ProductService(
          products,
          positions,
          t,
          mock(LineRepository.class),
          mock(ZoneRepository.class),
          mock(StorageService.class),
          adjustments);
    }

    @Bean
    OrderService orderService(
        OrderRepository orders,
        ProductRepository products,
        ProductService productService,
        IdempotencyRecordRepository idempotency) {
      StopResolver stops = mock(StopResolver.class);
      when(stops.resolve(any())).thenReturn(List.of());
      return new OrderService(
          orders,
          products,
          productService,
          mock(VehicleRepository.class),
          mock(VehicleUpdateExecutor.class),
          mock(StockDrainPort.class),
          stops,
          List.of(),
          List.of(),
          idempotency);
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
    // Deliberately NOT creating stock_guards: production has no such collection until the first
    // transaction upserts into it, and MongoDB 4.4 must create it implicitly there.
    for (String name : List.of("positions", "orders", "products", "idempotency_records")) {
      if (!template.collectionExists(name)) {
        template.createCollection(name);
      }
    }
    template
        .indexOps("idempotency_records")
        .createIndex(new Index().on("key", Sort.Direction.ASC).unique());
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
        List.of("positions", "orders", "products", "idempotency_records", "stock_guards")) {
      template.getCollection(name).deleteMany(new Document());
    }
    reset(KAFKA);
    when(KAFKA.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
  }

  private static void product(String id, int stock) {
    Product product = new Product();
    product.setId(id);
    product.setSku("SKU-" + id);
    product.setActive(true);
    template.save(product);
    Position p = new Position();
    p.setId("pos-" + id);
    p.setProductId(id);
    p.setActive(true);
    p.setMaximumCapacity(1000);
    p.setCurrentStock(stock);
    template.save(p);
  }

  private static CreateOrderRequest request(String productId, int quantity) {
    return new CreateOrderRequest(
        List.of(new OrderItemRequest(productId, quantity)),
        "AREA-A",
        new AddressRequest("Calle 1", null, null, "37000"),
        null);
  }

  private static <T> List<Object> runConcurrently(int n, Callable<T> task) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Object>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    return task.call();
                  } catch (Exception e) {
                    return e;
                  }
                }));
      }
      go.countDown();
      List<Object> results = new ArrayList<>();
      for (Future<Object> f : futures) {
        results.add(f.get(120, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static long orderCount() {
    return template.count(new Query(), Order.class);
  }

  @Test
  void parallelOrdersNeverOversell_theSurplusGetsInsufficientStock() throws Exception {
    product("prod-1", 10);
    OrderService service = context.getBean(OrderService.class);

    List<Object> results =
        runConcurrently(12, () -> service.createOrder(request("prod-1", 3), "user-1"));

    long created = results.stream().filter(r -> r instanceof Order).count();
    List<Object> rejected = results.stream().filter(r -> !(r instanceof Order)).toList();
    assertThat(created).isEqualTo(3);
    assertThat(rejected)
        .hasSize(9)
        .allSatisfy(
            r ->
                assertThat(r)
                    .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> {
                          assertThat(e.getStatusCode().value()).isEqualTo(400);
                          assertThat(e.getReason()).isEqualTo("INSUFFICIENT_STOCK");
                        }));
    assertThat(orderCount()).isEqualTo(3);
    assertThat(
            template.find(new Query(), Order.class).stream()
                .filter(o -> o.getStatus() == OrderStatus.PENDING)
                .mapToInt(o -> o.getItems().get(0).getQuantity())
                .sum())
        .isLessThanOrEqualTo(10);
  }

  @Test
  void parallelOrdersOfDifferentProductsDoNotBlockEachOther() throws Exception {
    product("prod-a", 100);
    product("prod-b", 100);
    OrderService service = context.getBean(OrderService.class);

    List<Object> results =
        runConcurrently(
            8,
            () -> {
              String id = Thread.currentThread().getId() % 2 == 0 ? "prod-a" : "prod-b";
              return service.createOrder(request(id, 1), "user-1");
            });

    assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(Order.class));
    assertThat(orderCount()).isEqualTo(8);
  }

  @Test
  void parallelRequestsWithTheSameIdempotencyKeyReplayTheFirstOrder() throws Exception {
    product("prod-1", 100);
    OrderService service = context.getBean(OrderService.class);

    List<Object> results =
        runConcurrently(8, () -> service.createOrder(request("prod-1", 2), "user-1", "key-1"));

    assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(Order.class));
    assertThat(results.stream().map(r -> ((Order) r).getId()).distinct()).hasSize(1);
    assertThat(orderCount()).isEqualTo(1);
    assertThat(template.count(new Query(), IdempotencyRecord.class)).isEqualTo(1);
  }

  @Test
  void anOrderRacingAStockAdjustmentIsEvaluatedAgainstTheAdjustedStock() throws Exception {
    product("prod-1", 10);
    OrderService orders = context.getBean(OrderService.class);
    StockAdjustmentService adjustments = context.getBean(StockAdjustmentService.class);
    TransactionTemplate tx =
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    CountDownLatch adjusted = new CountDownLatch(1);
    CountDownLatch orderTried = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // The adjustment leaves 2 units but has not committed while the order is being created.
      Future<?> adjustment =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      s -> {
                        adjustments.adjust(
                            "pos-prod-1", 8, StockAdjustmentType.MANUAL_DECREASE, null);
                        adjusted.countDown();
                        try {
                          orderTried.await(30, TimeUnit.SECONDS);
                          Thread.sleep(300);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      }));
      assertThat(adjusted.await(30, TimeUnit.SECONDS)).isTrue();
      Future<Order> order =
          pool.submit(
              () -> {
                orderTried.countDown();
                return orders.createOrder(request("prod-1", 5), "user-1");
              });

      adjustment.get(30, TimeUnit.SECONDS);
      assertThat(order)
          .failsWithin(60, TimeUnit.SECONDS)
          .withThrowableOfType(ExecutionException.class)
          .havingCause()
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              e -> assertThat(e.getReason()).isEqualTo("INSUFFICIENT_STOCK"));
    } finally {
      pool.shutdownNow();
    }
    assertThat(orderCount()).isZero();
  }
}
