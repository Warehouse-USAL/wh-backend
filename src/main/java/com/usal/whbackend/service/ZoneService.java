package com.usal.whbackend.service;

import com.usal.whbackend.api.warehouse.zone.CreateZoneRequest;
import com.usal.whbackend.api.warehouse.zone.UpdateZoneRequest;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.service.exception.ZoneCodeAlreadyExistsException;
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ZoneService {

  private final ZoneRepository zoneRepository;

  public ZoneService(ZoneRepository zoneRepository) {
    this.zoneRepository = zoneRepository;
  }

  public List<Zone> getZones() {
    return zoneRepository.findAll();
  }

  public Zone getZone(String id) {
    return zoneRepository.findById(id).orElseThrow(() -> new ZoneNotFoundException(id));
  }

  public Zone createZone(CreateZoneRequest request) {
    if (zoneRepository.findByZoneCode(request.zoneCode()).isPresent()) {
      throw new ZoneCodeAlreadyExistsException(request.zoneCode());
    }
    Zone zone = new Zone();
    zone.setZoneCode(request.zoneCode());
    zone.setMaxAllowedLines(request.maxAllowedLines());
    zone.setActive(false);
    zone.setCreatedAt(Instant.now());
    return zoneRepository.save(zone);
  }

  public Zone updateZone(String id, UpdateZoneRequest request) {
    Zone zone = zoneRepository.findById(id).orElseThrow(() -> new ZoneNotFoundException(id));
    if (request.zoneCode() != null) zone.setZoneCode(request.zoneCode());
    if (request.maxAllowedLines() != null) zone.setMaxAllowedLines(request.maxAllowedLines());
    if (request.isActive() != null) zone.setActive(request.isActive());
    return zoneRepository.save(zone);
  }

  public void deleteZone(String id) {
    Zone zone = zoneRepository.findById(id).orElseThrow(() -> new ZoneNotFoundException(id));
    zone.setActive(false);
    zoneRepository.save(zone);
  }
}
