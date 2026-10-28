package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.ReceptionRepository;
import com.usal.whbackend.repository.RestockOrderRepository;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.repository.ZoneRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class DemoDataSeederTest {

  @Mock UserRepository userRepository;
  @Mock ProductRepository productRepository;
  @Mock ZoneRepository zoneRepository;
  @Mock LineRepository lineRepository;
  @Mock PositionRepository positionRepository;
  @Mock VehicleRepository vehicleRepository;
  @Mock OrderMongoRepository orderMongoRepository;
  @Mock RestockOrderRepository restockOrderRepository;
  @Mock ReceptionRepository receptionRepository;
  @Mock PasswordEncoder passwordEncoder;
  @Mock DemoTelemetrySeeder demoTelemetrySeeder;
  @Mock DemoOrderEventPublisher demoOrderEventPublisher;

  private DemoDataSeeder seeder(boolean seedDemo) {
    return new DemoDataSeeder(
        userRepository,
        productRepository,
        zoneRepository,
        lineRepository,
        positionRepository,
        vehicleRepository,
        orderMongoRepository,
        restockOrderRepository,
        receptionRepository,
        passwordEncoder,
        demoTelemetrySeeder,
        demoOrderEventPublisher,
        seedDemo,
        DemoDataset.DEFAULT_QR_PREFIX);
  }

  @Test
  void run_whenFlagDisabled_doesNothing() {
    seeder(false).run(null);

    verifyNoInteractions(
        userRepository,
        productRepository,
        zoneRepository,
        lineRepository,
        positionRepository,
        vehicleRepository,
        orderMongoRepository,
        restockOrderRepository,
        receptionRepository,
        passwordEncoder);
  }

  @Test
  void run_whenProductsAlreadyExist_skipsSeeding() {
    when(productRepository.count()).thenReturn(5L);

    seeder(true).run(null);

    verify(productRepository).count();
    verify(productRepository, never()).saveAll(any());
    verifyNoInteractions(demoOrderEventPublisher);
    verifyNoInteractions(
        userRepository,
        zoneRepository,
        lineRepository,
        positionRepository,
        vehicleRepository,
        orderMongoRepository,
        restockOrderRepository,
        receptionRepository,
        passwordEncoder);
  }

  @Test
  void run_whenOrdersRemainButProductsWereDeleted_skipsInsteadOfCrashing() {
    // Bulk inserts would hit duplicate ids and fail the boot; a partly emptied DB is not fresh.
    when(productRepository.count()).thenReturn(0L);
    when(orderMongoRepository.count()).thenReturn(12L);

    seeder(true).run(null);

    verify(orderMongoRepository, never())
        .insert(org.mockito.ArgumentMatchers.<Iterable<Order>>any());
    verifyNoInteractions(demoOrderEventPublisher);
    verifyNoInteractions(
        userRepository, restockOrderRepository, receptionRepository, passwordEncoder);
  }

  @Test
  void run_whenTheWarehouseHasOtherZones_skipsInsteadOfDuplicatingTheStructure() {
    // An older deployment's zones cannot be merged with the demo's zone A / line 1 / six positions.
    when(productRepository.count()).thenReturn(0L);
    when(orderMongoRepository.count()).thenReturn(0L);
    when(zoneRepository.count()).thenReturn(2L);
    when(zoneRepository.existsById("z-A")).thenReturn(false);

    seeder(true).run(null);

    verify(zoneRepository, never()).saveAll(any());
    verify(positionRepository, never()).saveAll(any());
    verifyNoInteractions(demoOrderEventPublisher);
    verifyNoInteractions(userRepository, restockOrderRepository, receptionRepository);
  }

  @Test
  void run_whenOnlyTheBootstrapWarehouseExists_fillsItInPlace() {
    when(productRepository.count()).thenReturn(0L);
    when(zoneRepository.count()).thenReturn(1L);
    when(zoneRepository.existsById("z-A")).thenReturn(true);
    when(passwordEncoder.encode(anyString())).thenReturn("$2a$hashed");

    seeder(true).run(null);

    // Same ids as the bootstrap's documents, so saveAll replaces them instead of adding to them.
    verify(zoneRepository)
        .saveAll(
            argThat(
                (Iterable<Zone> it) ->
                    count(it) == 1 && it.iterator().next().getId().equals("z-A")));
    verify(positionRepository).saveAll(argThat((Iterable<Position> it) -> count(it) == 6));
  }

  @Test
  void run_whenEnabledAndEmpty_seedsEveryCollection() {
    when(productRepository.count()).thenReturn(0L);
    when(passwordEncoder.encode(anyString())).thenReturn("$2a$hashed");

    Logger logger = (Logger) LoggerFactory.getLogger(DemoDataSeeder.class);
    ListAppender<ILoggingEvent> lines = new ListAppender<>();
    lines.start();
    logger.addAppender(lines);
    try {
      seeder(true).run(null);
    } finally {
      logger.detachAppender(lines);
    }

    assertThat(lines.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .isNotEmpty()
        .noneMatch(line -> line.contains(DemoDataset.SHARED_PASSWORD));
    verify(userRepository).saveAll(argThat((Iterable<User> it) -> count(it) == 13));
    verify(productRepository).saveAll(argThat((Iterable<Product> it) -> count(it) == 20));
    verify(zoneRepository).saveAll(argThat((Iterable<Zone> it) -> count(it) == 1));
    verify(lineRepository).saveAll(argThat((Iterable<Line> it) -> count(it) == 1));
    verify(positionRepository).saveAll(argThat((Iterable<Position> it) -> count(it) == 6));
    verify(vehicleRepository).saveAll(argThat((Iterable<Vehicle> it) -> count(it) == 6));
    // Bulk inserts: two years of history is ~22k orders, too many for one upsert per document.
    verify(orderMongoRepository).insert(argThat((Iterable<Order> it) -> count(it) > 12_000));
    verify(restockOrderRepository).insert(argThat((Iterable<RestockOrder> it) -> count(it) > 60));
    verify(receptionRepository).insert(argThat((Iterable<Reception> it) -> count(it) > 60));
    // Seeded inside the same fresh-database guard: the metrics store has no backfill, so the
    // rover charts would otherwise be blank next to a year of orders.
    verify(demoTelemetrySeeder).seed(argThat((java.util.List<Vehicle> it) -> it.size() == 6));
    // The suggestions model is preloaded with the very orders just inserted.
    verify(demoOrderEventPublisher)
        .publishAsync(argThat((java.util.List<Order> it) -> it.size() > 12_000));
  }

  private static int count(Iterable<?> iterable) {
    int c = 0;
    for (Object ignored : iterable) {
      c++;
    }
    return c;
  }
}
