package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.ZoneRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StopResolverTest {

  private StockDrainPort stockDrainPort;
  private ZoneRepository zoneRepository;
  private LineRepository lineRepository;
  private StopResolver resolver;

  @BeforeEach
  void setUp() {
    stockDrainPort = mock(StockDrainPort.class);
    zoneRepository = mock(ZoneRepository.class);
    lineRepository = mock(LineRepository.class);
    resolver = new StopResolver(stockDrainPort, zoneRepository, lineRepository);

    Zone a = zone("z-A", "A");
    Zone b = zone("z-B", "B");
    when(zoneRepository.findAllById(org.mockito.ArgumentMatchers.<Iterable<String>>any()))
        .thenReturn(List.of(a, b));
    when(lineRepository.findAllById(org.mockito.ArgumentMatchers.<Iterable<String>>any()))
        .thenReturn(List.of(line("l-A-1", 1), line("l-A-2", 2), line("l-B-1", 1)));
  }

  private static Zone zone(String id, String code) {
    Zone z = new Zone();
    z.setId(id);
    z.setZoneCode(code);
    return z;
  }

  private static Line line(String id, int number) {
    Line l = new Line();
    l.setId(id);
    l.setNumberLine(number);
    return l;
  }

  private static Position position(String id, String zone, String line, String name, String qr) {
    Position p = new Position();
    p.setId(id);
    p.setIdZone(zone);
    p.setIdLine(line);
    p.setPositionName(name);
    p.setQrCode(qr);
    return p;
  }

  private static final List<OrderItem> ITEMS = List.of(new OrderItem("prod-1", "SKU-1", 1));

  @Test
  void resolve_ordersStopsByZoneThenLineThenPositionName() {
    when(stockDrainPort.positionsToConsume(ITEMS))
        .thenReturn(
            List.of(
                position("p1", "z-B", "l-B-1", "P01", "QR-B1-P01"),
                position("p2", "z-A", "l-A-2", "P01", "QR-A2-P01"),
                position("p3", "z-A", "l-A-1", "P02", "QR-A1-P02"),
                position("p4", "z-A", "l-A-1", "P01", "QR-A1-P01")));

    assertThat(resolver.resolve(ITEMS))
        .containsExactly("QR-A1-P01", "QR-A1-P02", "QR-A2-P01", "QR-B1-P01");
  }

  @Test
  void resolve_visitsEachPositionOnce() {
    Position shared = position("p1", "z-A", "l-A-1", "P01", "QR-A1-P01");
    when(stockDrainPort.positionsToConsume(ITEMS)).thenReturn(List.of(shared, shared));

    assertThat(resolver.resolve(ITEMS)).containsExactly("QR-A1-P01");
  }

  @Test
  void resolve_leavesOutPositionsWithoutQrCode() {
    when(stockDrainPort.positionsToConsume(ITEMS))
        .thenReturn(
            List.of(
                position("p1", "z-A", "l-A-1", "P01", null),
                position("p2", "z-A", "l-A-1", "P02", " "),
                position("p3", "z-A", "l-A-1", "P03", "QR-A1-P03")));

    assertThat(resolver.resolve(ITEMS)).containsExactly("QR-A1-P03");
  }

  @Test
  void resolve_withNothingToConsume_returnsNoStops() {
    when(stockDrainPort.positionsToConsume(ITEMS)).thenReturn(List.of());

    assertThat(resolver.resolve(ITEMS)).isEmpty();
  }

  @Test
  void resolve_withUnknownZoneAndLine_stillReturnsTheStopsLast() {
    when(stockDrainPort.positionsToConsume(ITEMS))
        .thenReturn(
            List.of(
                position("p1", "z-gone", "l-gone", "P01", "QR-ORPHAN"),
                position("p2", "z-A", "l-A-1", "P01", "QR-A1-P01")));

    assertThat(resolver.resolve(ITEMS)).containsExactly("QR-A1-P01", "QR-ORPHAN");
  }
}
