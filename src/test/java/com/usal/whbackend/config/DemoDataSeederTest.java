package com.usal.whbackend.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.repository.ZoneRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
  @Mock PasswordEncoder passwordEncoder;
  @Mock DemoTelemetrySeeder demoTelemetrySeeder;

  private DemoDataSeeder seeder(boolean seedDemo) {
    return new DemoDataSeeder(
        userRepository,
        productRepository,
        zoneRepository,
        lineRepository,
        positionRepository,
        vehicleRepository,
        orderMongoRepository,
        passwordEncoder,
        demoTelemetrySeeder,
        seedDemo);
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
        passwordEncoder);
  }

  @Test
  void run_whenProductsAlreadyExist_skipsSeeding() {
    when(productRepository.count()).thenReturn(5L);

    seeder(true).run(null);

    verify(productRepository).count();
    verify(productRepository, never()).saveAll(any());
    verifyNoInteractions(
        userRepository,
        zoneRepository,
        lineRepository,
        positionRepository,
        vehicleRepository,
        orderMongoRepository,
        passwordEncoder);
  }

  @Test
  void run_whenEnabledAndEmpty_seedsEveryCollection() {
    when(productRepository.count()).thenReturn(0L);
    when(passwordEncoder.encode(anyString())).thenReturn("$2a$hashed");

    seeder(true).run(null);

    verify(userRepository).saveAll(argThat((Iterable<User> it) -> count(it) == 13));
    verify(productRepository).saveAll(argThat((Iterable<Product> it) -> count(it) == 24));
    verify(zoneRepository).saveAll(argThat((Iterable<Zone> it) -> count(it) == 2));
    verify(lineRepository).saveAll(argThat((Iterable<Line> it) -> count(it) == 7));
    verify(positionRepository).saveAll(argThat((Iterable<Position> it) -> count(it) == 35));
    verify(vehicleRepository).saveAll(argThat((Iterable<Vehicle> it) -> count(it) == 6));
    verify(orderMongoRepository).saveAll(argThat((Iterable<Order> it) -> count(it) == 25));
    // Seeded inside the same fresh-database guard: the metrics store has no backfill, so the
    // rover charts would otherwise be blank next to three weeks of orders.
    verify(demoTelemetrySeeder).seed(argThat((java.util.List<Vehicle> it) -> it.size() == 6));
  }

  private static int count(Iterable<?> iterable) {
    int c = 0;
    for (Object ignored : iterable) {
      c++;
    }
    return c;
  }
}
