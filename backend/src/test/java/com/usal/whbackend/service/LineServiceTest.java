package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.warehouse.line.CreateLineRequest;
import com.usal.whbackend.api.warehouse.line.UpdateLineRequest;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.service.exception.LineNotFoundException;
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
    z.setActive(true);
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

  @Test
  void getLine_found_returnsLine() {
    when(lineRepository.findById("l1")).thenReturn(Optional.of(line("l1", "z1", 1)));
    assertEquals(1, lineService.getLine("l1").getNumberLine());
  }

  @Test
  void getLine_notFound_throwsLineNotFound() {
    when(lineRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(LineNotFoundException.class, () -> lineService.getLine("bad"));
  }

  @Test
  void createLine_inactiveZone_throwsBadRequest() {
    Zone z = zone("z1");
    z.setActive(false);
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(z));
    CreateLineRequest req = new CreateLineRequest(1, 20);
    var ex =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> lineService.createLine("z1", req));
    assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void createLine_zoneNotFound_throwsZoneNotFound() {
    when(zoneRepository.findById("bad")).thenReturn(Optional.empty());
    CreateLineRequest req = new CreateLineRequest(1, 20);
    assertThrows(ZoneNotFoundException.class, () -> lineService.createLine("bad", req));
  }

  @Test
  void updateLine_notFound_throwsLineNotFound() {
    when(lineRepository.findById("bad")).thenReturn(Optional.empty());
    UpdateLineRequest req = new UpdateLineRequest(null, null, null);
    assertThrows(LineNotFoundException.class, () -> lineService.updateLine("bad", req));
  }

  @Test
  void updateLine_allFields_appliesChanges() {
    Line l = line("l1", "z1", 1);
    when(lineRepository.findById("l1")).thenReturn(Optional.of(l));
    when(lineRepository.findByIdZoneAndNumberLine("z1", 5)).thenReturn(Optional.empty());
    when(lineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Line result = lineService.updateLine("l1", new UpdateLineRequest(5, 30, true));

    assertEquals(5, result.getNumberLine());
    assertEquals(30, result.getMaxAllowedPositions());
    assertTrue(result.isActive());
  }

  @Test
  void updateLine_numberTakenByAnotherLine_throwsLineNumberAlreadyExists() {
    Line l = line("l1", "z1", 1);
    when(lineRepository.findById("l1")).thenReturn(Optional.of(l));
    when(lineRepository.findByIdZoneAndNumberLine("z1", 2))
        .thenReturn(Optional.of(line("l2", "z1", 2)));

    UpdateLineRequest req = new UpdateLineRequest(2, null, null);
    assertThrows(LineNumberAlreadyExistsException.class, () -> lineService.updateLine("l1", req));
  }

  @Test
  void updateLine_keepingItsOwnNumber_isAllowed() {
    Line l = line("l1", "z1", 1);
    when(lineRepository.findById("l1")).thenReturn(Optional.of(l));
    when(lineRepository.findByIdZoneAndNumberLine("z1", 1)).thenReturn(Optional.of(l));
    when(lineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    assertEquals(
        1, lineService.updateLine("l1", new UpdateLineRequest(1, null, null)).getNumberLine());
  }

  @Test
  void updateLine_noFields_leavesLineUntouched() {
    Line l = line("l1", "z1", 1);
    when(lineRepository.findById("l1")).thenReturn(Optional.of(l));
    when(lineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Line result = lineService.updateLine("l1", new UpdateLineRequest(null, null, null));

    assertEquals(1, result.getNumberLine());
    assertEquals(20, result.getMaxAllowedPositions());
    assertFalse(result.isActive());
  }

  @Test
  void deleteLine_notFound_throwsLineNotFound() {
    when(lineRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(LineNotFoundException.class, () -> lineService.deleteLine("bad"));
  }

  @Test
  void updateLineRequest_exposesComponents() {
    UpdateLineRequest req = new UpdateLineRequest(3, 15, true);
    assertEquals(3, req.numberLine());
    assertEquals(15, req.maxAllowedPositions());
    assertTrue(req.isActive());
    assertEquals(req, new UpdateLineRequest(3, 15, true));
    assertEquals(req.hashCode(), new UpdateLineRequest(3, 15, true).hashCode());
    assertTrue(req.toString().contains("3"));
  }
}
