package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.warehouse.line.CreateLineRequest;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.service.exception.LineNumberAlreadyExistsException;
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LineServiceTest {

  @Mock LineRepository lineRepository;
  @Mock ZoneRepository zoneRepository;
  @InjectMocks LineService lineService;

  private Zone zone(String id) {
    Zone z = new Zone();
    z.setId(id);
    z.setZoneCode("A");
    return z;
  }

  private Line line(String id, String zoneId, int number) {
    Line l = new Line();
    l.setId(id);
    l.setIdZone(zoneId);
    l.setNumberLine(number);
    l.setActive(false);
    l.setMaxAllowedPositions(20);
    return l;
  }

  @Test
  void getLinesByZone_returnsLinesForZone() {
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(zone("z1")));
    when(lineRepository.findByIdZone("z1")).thenReturn(List.of(line("l1", "z1", 1)));
    assertEquals(1, lineService.getLinesByZone("z1").size());
  }

  @Test
  void getLinesByZone_zoneNotFound_throws() {
    when(zoneRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(ZoneNotFoundException.class, () -> lineService.getLinesByZone("bad"));
  }

  @Test
  void createLine_duplicateNumber_throwsLineNumberAlreadyExists() {
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(zone("z1")));
    when(lineRepository.findByIdZoneAndNumberLine("z1", 1))
        .thenReturn(Optional.of(line("l1", "z1", 1)));
    assertThrows(
        LineNumberAlreadyExistsException.class,
        () -> lineService.createLine("z1", new CreateLineRequest(1, 20)));
  }

  @Test
  void createLine_valid_savesWithZoneId() {
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(zone("z1")));
    when(lineRepository.findByIdZoneAndNumberLine("z1", 2)).thenReturn(Optional.empty());
    Line saved = line("l2", "z1", 2);
    when(lineRepository.save(any())).thenReturn(saved);
    Line result = lineService.createLine("z1", new CreateLineRequest(2, 20));
    assertEquals("z1", result.getIdZone());
  }

  @Test
  void deleteLine_softDeletes() {
    Line l = line("l1", "z1", 1);
    l.setActive(true);
    when(lineRepository.findById("l1")).thenReturn(Optional.of(l));
    when(lineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    lineService.deleteLine("l1");
    verify(lineRepository).save(argThat(saved -> !((Line) saved).isActive()));
  }
}
