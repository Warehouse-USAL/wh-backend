package com.usal.whbackend.service;

import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.ZoneRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns an order's items into the ordered list of places a vehicle has to visit: the QR text of
 * every position the stock drain will take from, once per position, in warehouse traversal order
 * (zone code, then line number, then position name).
 */
@Service
public class StopResolver {

  private static final Logger log = LoggerFactory.getLogger(StopResolver.class);

  private static final Comparator<String> NULLS_LAST =
      Comparator.nullsLast(Comparator.naturalOrder());

  private final StockDrainPort stockDrainPort;
  private final ZoneRepository zoneRepository;
  private final LineRepository lineRepository;

  public StopResolver(
      StockDrainPort stockDrainPort, ZoneRepository zoneRepository, LineRepository lineRepository) {
    this.stockDrainPort = stockDrainPort;
    this.zoneRepository = zoneRepository;
    this.lineRepository = lineRepository;
  }

  /**
   * Positions that hold stock but have no QR code yet cannot be visited, so they are left out (and
   * logged) rather than failing the order: an order whose stops come back empty is still created,
   * and Infra answers a pending order without stops by cancelling it.
   */
  public List<String> resolve(List<OrderItem> items) {
    Map<String, Position> distinct = new LinkedHashMap<>();
    for (Position position : stockDrainPort.positionsToConsume(items)) {
      distinct.putIfAbsent(position.getId(), position);
    }
    if (distinct.isEmpty()) {
      return List.of();
    }

    Map<String, Zone> zones =
        zoneRepository
            .findAllById(distinct.values().stream().map(Position::getIdZone).distinct().toList())
            .stream()
            .collect(Collectors.toMap(Zone::getId, Function.identity()));
    Map<String, Line> lines =
        lineRepository
            .findAllById(distinct.values().stream().map(Position::getIdLine).distinct().toList())
            .stream()
            .collect(Collectors.toMap(Line::getId, Function.identity()));

    Comparator<Position> traversal =
        Comparator.comparing((Position p) -> zoneCode(zones, p), NULLS_LAST)
            .thenComparingInt(p -> lineNumber(lines, p))
            .thenComparing(Position::getPositionName, NULLS_LAST);

    return distinct.values().stream()
        .sorted(traversal)
        .filter(StopResolver::hasQrCode)
        .map(Position::getQrCode)
        .toList();
  }

  private static boolean hasQrCode(Position position) {
    if (position.getQrCode() == null || position.getQrCode().isBlank()) {
      log.warn(
          "Position {} ({}) has no QR code — left out of the order's stops",
          position.getId(),
          position.getPositionName());
      return false;
    }
    return true;
  }

  private static String zoneCode(Map<String, Zone> zones, Position position) {
    Zone zone = zones.get(position.getIdZone());
    return zone == null ? null : zone.getZoneCode();
  }

  private static int lineNumber(Map<String, Line> lines, Position position) {
    Line line = lines.get(position.getIdLine());
    return line == null ? Integer.MAX_VALUE : line.getNumberLine();
  }
}
