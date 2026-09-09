package com.usal.whbackend.api.warehouse.line;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.service.LineService;
import com.usal.whbackend.service.exception.LineNotFoundException;
import com.usal.whbackend.service.exception.LineNumberAlreadyExistsException;
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

@WebMvcTest(LineController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class LineControllerTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean LineService lineService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private Line line(String id, String zoneId) {
    Line l = new Line();
    l.setId(id);
    l.setIdZone(zoneId);
    l.setNumberLine(1);
    l.setActive(false);
    l.setMaxAllowedPositions(20);
    return l;
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getLines_returns200() throws Exception {
    when(lineService.getLinesByZone("z1")).thenReturn(List.of(line("l1", "z1")));
    mockMvc
        .perform(get("/warehouse/zones/z1/lines"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines").isArray())
        .andExpect(jsonPath("$.lines[0].id_line").value("l1"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createLine_valid_returns201() throws Exception {
    when(lineService.createLine(eq("z1"), any())).thenReturn(line("l1", "z1"));
    mockMvc
        .perform(
            post("/warehouse/zones/z1/lines")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"number_line\":1,\"max_allowed_positions\":20}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id_line").value("l1"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void deleteLine_notFound_returns404() throws Exception {
    doThrow(new LineNotFoundException("l99")).when(lineService).deleteLine("l99");
    mockMvc.perform(delete("/warehouse/lines/l99")).andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void updateLine_valid_returns200() throws Exception {
    Line updated = line("l1", "z1");
    updated.setNumberLine(7);
    when(lineService.updateLine(eq("l1"), any())).thenReturn(updated);

    mockMvc
        .perform(
            patch("/warehouse/lines/l1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"number_line\":7}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.number_line").value(7));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void updateLine_duplicateNumber_returns409() throws Exception {
    when(lineService.updateLine(eq("l1"), any()))
        .thenThrow(new LineNumberAlreadyExistsException(7, "z1"));

    mockMvc
        .perform(
            patch("/warehouse/lines/l1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"number_line\":7}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("LINE_NUMBER_ALREADY_EXISTS"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void updateLine_notFound_returns404() throws Exception {
    when(lineService.updateLine(eq("ghost"), any())).thenThrow(new LineNotFoundException("ghost"));

    mockMvc
        .perform(
            patch("/warehouse/lines/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"is_active\":true}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("LINE_NOT_FOUND"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void deleteLine_returns204() throws Exception {
    mockMvc.perform(delete("/warehouse/lines/l1")).andExpect(status().isNoContent());
    verify(lineService).deleteLine("l1");
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void deleteLine_insufficientRole_returns403() throws Exception {
    mockMvc.perform(delete("/warehouse/lines/l1")).andExpect(status().isForbidden());
  }
}
