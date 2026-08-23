package com.usal.whbackend.config;

import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.Zone;
import java.util.List;

/**
 * Immutable container for a fully-built, interconnected demo dataset. Lists are defensively copied
 * on the way in and on the way out so the holder never leaks its internal state.
 */
public final class DemoData {

  private final List<User> users;
  private final List<Product> products;
  private final List<Zone> zones;
  private final List<Line> lines;
  private final List<Position> positions;
  private final List<Vehicle> vehicles;
  private final List<Order> orders;

  public DemoData(
      List<User> users,
      List<Product> products,
      List<Zone> zones,
      List<Line> lines,
      List<Position> positions,
      List<Vehicle> vehicles,
      List<Order> orders) {
    this.users = List.copyOf(users);
    this.products = List.copyOf(products);
    this.zones = List.copyOf(zones);
    this.lines = List.copyOf(lines);
    this.positions = List.copyOf(positions);
    this.vehicles = List.copyOf(vehicles);
    this.orders = List.copyOf(orders);
  }

  public List<User> getUsers() {
    return List.copyOf(users);
  }

  public List<Product> getProducts() {
    return List.copyOf(products);
  }

  public List<Zone> getZones() {
    return List.copyOf(zones);
  }

  public List<Line> getLines() {
    return List.copyOf(lines);
  }

  public List<Position> getPositions() {
    return List.copyOf(positions);
  }

  public List<Vehicle> getVehicles() {
    return List.copyOf(vehicles);
  }

  public List<Order> getOrders() {
    return List.copyOf(orders);
  }
}
