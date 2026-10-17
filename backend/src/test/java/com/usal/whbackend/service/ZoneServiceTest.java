package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.warehouse.zone.CreateZoneRequest;
import com.usal.whbackend.api.warehouse.zone.UpdateZoneRequest;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.service.exception.ZoneCodeAlreadyExistsException;
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ZoneServiceTest {

  @Mock ZoneRepository zoneRepository;
  @InjectMocks ZoneService zoneService;

  private Zone zone(String id, String code) {
    Zone z = new Zone();
    z.setId(id);
    z.setZoneCode(code);
    z.setActive(false);
    z.setMaxAllowedLines(10);
    return z;
  }

  @Test
  void getZones_returnsAll() {
    when(zoneRepository.findAll()).thenReturn(List.of(zone("z1", "A")));
    assertEquals(1, zoneService.getZones().size());
  }

  @Test
  void createZone_duplicateCode_throwsZoneCodeAlreadyExists() {
    when(zoneRepository.findByZoneCode("A")).thenReturn(Optional.of(zone("z1", "A")));
    CreateZoneRequest req = new CreateZoneRequest("A", 10);
    assertThrows(ZoneCodeAlreadyExistsException.class, () -> zoneService.createZone(req));
  }

  @Test
  void createZone_newCode_savesAndReturns() {
    when(zoneRepository.findByZoneCode("B")).thenReturn(Optional.empty());
    Zone saved = zone("z2", "B");
    when(zoneRepository.save(any())).thenReturn(saved);
    Zone result = zoneService.createZone(new CreateZoneRequest("B", 5));
    assertEquals("B", result.getZoneCode());
    assertFalse(result.isActive());
  }

  @Test
  void getZone_byId_returnsZone() {
    Zone z = zone("z1", "A");
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(z));
    assertEquals("A", zoneService.getZone("z1").getZoneCode());
  }

  @Test
  void updateZone_notFound_throwsZoneNotFound() {
    when(zoneRepository.findById("missing")).thenReturn(Optional.empty());
    assertThrows(
        ZoneNotFoundException.class,
        () -> zoneService.updateZone("missing", new UpdateZoneRequest(null, null, null)));
  }

  @Test
  void updateZone_duplicateCode_throwsZoneCodeAlreadyExists() {
    Zone existing = zone("z1", "A");
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(existing));
    when(zoneRepository.findByZoneCode("B")).thenReturn(Optional.of(zone("z2", "B")));
    assertThrows(
        ZoneCodeAlreadyExistsException.class,
        () -> zoneService.updateZone("z1", new UpdateZoneRequest("B", null, null)));
  }

  @Test
  void deleteZone_softDeletes() {
    Zone z = zone("z1", "A");
    z.setActive(true);
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(z));
    when(zoneRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    zoneService.deleteZone("z1");
    verify(zoneRepository).save(argThat(saved -> !((Zone) saved).isActive()));
  }

  @Test
  void getZone_notFound_throwsZoneNotFound() {
    when(zoneRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(ZoneNotFoundException.class, () -> zoneService.getZone("bad"));
  }

  @Test
  void updateZone_allFields_appliesChanges() {
    Zone z = zone("z1", "A");
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(z));
    when(zoneRepository.findByZoneCode("B")).thenReturn(Optional.empty());
    when(zoneRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Zone result = zoneService.updateZone("z1", new UpdateZoneRequest("B", 42, true));

    assertEquals("B", result.getZoneCode());
    assertEquals(42, result.getMaxAllowedLines());
    assertTrue(result.isActive());
  }

  @Test
  void updateZone_keepingItsOwnCode_skipsUniquenessCheck() {
    Zone z = zone("z1", "A");
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(z));
    when(zoneRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Zone result = zoneService.updateZone("z1", new UpdateZoneRequest("A", null, null));

    assertEquals("A", result.getZoneCode());
    verify(zoneRepository, never()).findByZoneCode(any());
  }

  @Test
  void updateZone_noFields_leavesZoneUntouched() {
    Zone z = zone("z1", "A");
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(z));
    when(zoneRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Zone result = zoneService.updateZone("z1", new UpdateZoneRequest(null, null, null));

    assertEquals("A", result.getZoneCode());
    assertEquals(10, result.getMaxAllowedLines());
    assertFalse(result.isActive());
  }

  @Test
  void deleteZone_notFound_throwsZoneNotFound() {
    when(zoneRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(ZoneNotFoundException.class, () -> zoneService.deleteZone("bad"));
  }
}
