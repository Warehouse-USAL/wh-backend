package com.usal.whbackend.api.restock.reception;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.service.ReceptionService;
import com.usal.whbackend.service.exception.ReceptionNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(ReceptionController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class ReceptionControllerTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean ReceptionService receptionService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private Reception reception(String id) {
    Reception r = new Reception();
    r.setId(id);
    r.setProductId("product-1");
    r.setQuantityReceived(48);
    r.setDeliveryUnit(StockSize.PALLET);
    r.setSupplier("Distribuidora XYZ");
    r.setAssignments(
        List.of(new Reception.Assignment("pos-1", 30), new Reception.Assignment("pos-2", 18)));
    r.setReceivedByUserId("user-1");
    r.setCreatedAt(Instant.parse("2026-08-26T14:00:00Z"));
    return r;
  }

  private static final String VALID_BODY =
      "{\"product_id\":\"product-1\",\"quantity_received\":48,\"delivery_unit\":\"PALLET\","
          + "\"supplier\":\"Distribuidora XYZ\",\"assignments\":["
          + "{\"position_id\":\"pos-1\",\"quantity\":30},"
          + "{\"position_id\":\"pos-2\",\"quantity\":18}]}";

  @Test
  @WithMockUser(username = "user-1", roles = "ADMIN_WAREHOUSE")
  void createReception_valid_returns201() throws Exception {
    when(receptionService.createReception(any(), eq("user-1"))).thenReturn(reception("rcp-1"));

    mockMvc
        .perform(
            post("/restock/receptions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.reception.id").value("rcp-1"))
        .andExpect(jsonPath("$.reception.quantity_received").value(48))
        .andExpect(jsonPath("$.reception.assignments[0].position_id").value("pos-1"))
        .andExpect(jsonPath("$.reception.assignments[1].quantity").value(18));
  }

  @Test
  @WithMockUser(roles = "PROVIDER")
  void createReception_unauthorizedRole_returns403() throws Exception {
    mockMvc
        .perform(
            post("/restock/receptions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createReception_emptyAssignments_returns400() throws Exception {
    mockMvc
        .perform(
            post("/restock/receptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"product_id\":\"product-1\",\"quantity_received\":48,\"delivery_unit\":\"PALLET\","
                        + "\"supplier\":\"XYZ\",\"assignments\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createReception_assignmentMismatch_returns400() throws Exception {
    when(receptionService.createReception(any(), any()))
        .thenThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "ASSIGNMENT_QUANTITY_MISMATCH"));

    mockMvc
        .perform(
            post("/restock/receptions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_QUANTITY_MISMATCH"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getReceptions_returns200WithPagination() throws Exception {
    when(receptionService.getReceptions(any(), any(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(reception("rcp-1")), PageRequest.of(0, 10), 1));

    mockMvc
        .perform(get("/restock/receptions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.receptions[0].id").value("rcp-1"))
        .andExpect(jsonPath("$.pagination.total_elements").value(1));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getReception_found_returnsBreakdown() throws Exception {
    when(receptionService.getReception("rcp-1")).thenReturn(reception("rcp-1"));

    mockMvc
        .perform(get("/restock/receptions/rcp-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reception.assignments.length()").value(2));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getReception_notFound_returns404() throws Exception {
    when(receptionService.getReception("bad")).thenThrow(new ReceptionNotFoundException("bad"));

    mockMvc
        .perform(get("/restock/receptions/bad"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RECEPTION_NOT_FOUND"));
  }
}
