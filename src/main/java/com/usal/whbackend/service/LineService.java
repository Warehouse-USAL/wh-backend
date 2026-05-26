package com.usal.whbackend.service;

import com.usal.whbackend.api.warehouse.line.CreateLineRequest;
import com.usal.whbackend.api.warehouse.line.UpdateLineRequest;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.service.exception.LineNotFoundException;
import com.usal.whbackend.service.exception.LineNumberAlreadyExistsException;
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LineService {

  private final LineRepository lineRepository;
  private final ZoneRepository zoneRepository;

  public LineService(LineRepository lineRepository, ZoneRepository zoneRepository) {
    this.lineRepository = lineRepository;
    this.zoneRepository = zoneRepository;
  }

  public List<Line> getLinesByZone(String zoneId) {
    zoneRepository.findById(zoneId).orElseThrow(() -> new ZoneNotFoundException(zoneId));
    return lineRepository.findByIdZone(zoneId);
  }

  public Line getLine(String id) {
    return lineRepository.findById(id).orElseThrow(() -> new LineNotFoundException(id));
  }

  public Line createLine(String zoneId, CreateLineRequest request) {
    zoneRepository.findById(zoneId).orElseThrow(() -> new ZoneNotFoundException(zoneId));
    if (lineRepository.findByIdZoneAndNumberLine(zoneId, request.numberLine()).isPresent()) {
      throw new LineNumberAlreadyExistsException(request.numberLine(), zoneId);
    }
    Line line = new Line();
    line.setIdZone(zoneId);
    line.setNumberLine(request.numberLine());
    line.setMaxAllowedPositions(request.maxAllowedPositions());
    line.setActive(false);
    line.setCreatedAt(Instant.now());
    return lineRepository.save(line);
  }

  public Line updateLine(String id, UpdateLineRequest request) {
    Line line = lineRepository.findById(id).orElseThrow(() -> new LineNotFoundException(id));
    if (request.numberLine() != null) {
      // Check uniqueness if changing number within the same zone
      if (lineRepository
          .findByIdZoneAndNumberLine(line.getIdZone(), request.numberLine())
          .filter(existing -> !existing.getId().equals(id))
          .isPresent()) {
        throw new LineNumberAlreadyExistsException(request.numberLine(), line.getIdZone());
      }
      line.setNumberLine(request.numberLine());
    }
    if (request.maxAllowedPositions() != null) {
      line.setMaxAllowedPositions(request.maxAllowedPositions());
    }
    if (request.isActive() != null) {
      line.setActive(request.isActive());
    }
    return lineRepository.save(line);
  }

  public void deleteLine(String id) {
    Line line = lineRepository.findById(id).orElseThrow(() -> new LineNotFoundException(id));
    line.setActive(false);
    lineRepository.save(line);
  }
}
