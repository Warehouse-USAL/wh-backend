package com.usal.whbackend.telemetry;

import java.util.List;

/**
 * Supplies the current fleet to the observable gauge, which reads it once per export interval.
 *
 * <p>Declared here and implemented in the service layer so the dependency arrow points inward: the
 * telemetry package never imports the repository or domain packages.
 */
public interface FleetStateSource {

  /** Every vehicle and its status right now. Never null; an empty fleet is valid. */
  List<VehicleState> currentFleet();

  /**
   * Every status a vehicle may be in.
   *
   * <p>Needed so the gauge can publish an explicit 0 for the states a vehicle is <em>not</em> in.
   * Reporting only the active state would leave the others simply absent, and an absent series
   * reads as its last value until it goes stale — so a rover that left BUSY would keep counting as
   * busy for the whole staleness window.
   */
  List<String> knownStates();
}
