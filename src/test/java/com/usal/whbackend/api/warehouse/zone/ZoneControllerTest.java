package com.usal.whbackend.api.warehouse.zone;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.service.ZoneService;
import com.usal.whbackend.service.exception.ZoneCodeAlreadyExistsException;
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(ZoneController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class ZoneControllerTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean ZoneService zoneService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private Zone zone(String id, String code) {
    Zone z = new Zone();
    z.setId(id);
    z.setZoneCode(code);
    z.setActive(false);
    z.setMaxAllowedLines(10);
    return z;
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getZones_returns200WithList() throws Exception {
    when(zoneService.getZones()).thenReturn(List.of(zone("z1", "A")));
    mockMvc
        .perform(get("/warehouse/zones"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.zones").isArray())
        .andExpect(jsonPath("$.zones[0].zone_code").value("A"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createZone_valid_returns201() throws Exception {
    when(zoneService.createZone(any())).thenReturn(zone("z1", "A"));
    mockMvc
        .perform(
            post("/warehouse/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone_code\":\"A\",\"max_allowed_lines\":10}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.zone_code").value("A"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createZone_duplicateCode_returns409() throws Exception {
    when(zoneService.createZone(any())).thenThrow(new ZoneCodeAlreadyExistsException("A"));
    mockMvc
        .perform(
            post("/warehouse/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone_code\":\"A\",\"max_allowed_lines\":10}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ZONE_CODE_ALREADY_EXISTS"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void deleteZone_notFound_returns404() throws Exception {
    doThrow(new ZoneNotFoundException("z99")).when(zoneService).deleteZone("z99");
    mockMvc
        .perform(delete("/warehouse/zones/z99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ZONE_NOT_FOUND"));
  }

  @Test
  void getZones_unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/warehouse/zones")).andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void updateZone_valid_returns200() throws Exception {
    Zone updated = new Zone();
    updated.setId("z1");
    updated.setZoneCode("B");
    updated.setMaxAllowedLines(10);
    when(zoneService.updateZone(eq("z1"), any())).thenReturn(updated);

    mockMvc
        .perform(
            patch("/warehouse/zones/z1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone_code\":\"B\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.zone_code").value("B"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void updateZone_duplicateCode_returns409() throws Exception {
    when(zoneService.updateZone(eq("z1"), any()))
        .thenThrow(new ZoneCodeAlreadyExistsException("B"));

    mockMvc
        .perform(
            patch("/warehouse/zones/z1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone_code\":\"B\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ZONE_CODE_ALREADY_EXISTS"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void updateZone_notFound_returns404() throws Exception {
    when(zoneService.updateZone(eq("ghost"), any())).thenThrow(new ZoneNotFoundException("ghost"));

    mockMvc
        .perform(
            patch("/warehouse/zones/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"is_active\":true}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ZONE_NOT_FOUND"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void deleteZone_returns204() throws Exception {
    mockMvc.perform(delete("/warehouse/zones/z1")).andExpect(status().isNoContent());
    verify(zoneService).deleteZone("z1");
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void deleteZone_insufficientRole_returns403() throws Exception {
    mockMvc.perform(delete("/warehouse/zones/z1")).andExpect(status().isForbidden());
  }
}
