package com.usal.whbackend.api.infra;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.service.OrderStatusService;
import com.usal.whbackend.service.VehicleTelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs the real security chain: what matters here is that a service error (404, 409, 400) reaches
 * the bridge as itself and is not masked as a 401 by the error dispatch.
 */
@WebMvcTest({InfraOrderController.class, InfraVehicleController.class})
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@TestPropertySource(properties = "infra.api-key=test-key")
class InfraControllerTest {

  private static final String STATUS_BODY =
      "{\"status\":\"in_progress\",\"vehicle_id\":\"vhc-1\",\"timestamp\":\"2026-05-01T10:06:00Z\"}";
  private static final String TELEMETRY_BODY =
      "{\"vehicle_id\":\"vhc-1\",\"status\":\"busy\",\"battery\":79,\"last_qr\":\"WH2|PA1\","
          + "\"error\":null,\"timestamp\":\"2026-05-01T10:06:30Z\"}";

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean OrderStatusService orderStatusService;
  @MockitoBean VehicleTelemetryService telemetryService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static MockHttpServletRequestBuilder statusRequest(String key) {
    MockHttpServletRequestBuilder request =
        post("/infra/orders/ord-1/status")
            .contentType(MediaType.APPLICATION_JSON)
            .content(STATUS_BODY);
    return key == null ? request : request.header("X-Api-Key", key);
  }

  private static MockHttpServletRequestBuilder telemetryRequest(String key) {
    MockHttpServletRequestBuilder request =
        post("/infra/vehicles/vhc-1/telemetry")
            .contentType(MediaType.APPLICATION_JSON)
            .content(TELEMETRY_BODY);
    return key == null ? request : request.header("X-Api-Key", key);
  }

  // ── authentication ──────────────────────────────────────────────────────────

  @Test
  void orderStatus_withoutApiKey_returns401() throws Exception {
    mockMvc.perform(statusRequest(null)).andExpect(status().isUnauthorized());
    verifyNoInteractions(orderStatusService);
  }

  @Test
  void orderStatus_withWrongApiKey_returns401() throws Exception {
    mockMvc.perform(statusRequest("nope")).andExpect(status().isUnauthorized());
    verifyNoInteractions(orderStatusService);
  }

  @Test
  void telemetry_withoutApiKey_returns401() throws Exception {
    mockMvc.perform(telemetryRequest(null)).andExpect(status().isUnauthorized());
    verifyNoInteractions(telemetryService);
  }

  @Test
  void telemetry_withWrongApiKey_returns401() throws Exception {
    mockMvc.perform(telemetryRequest("nope")).andExpect(status().isUnauthorized());
    verifyNoInteractions(telemetryService);
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void aLoggedInAdminWithoutTheApiKey_isStillRejected() throws Exception {
    mockMvc.perform(statusRequest(null)).andExpect(status().isUnauthorized());
  }

  // ── POST /infra/orders/{id}/status ──────────────────────────────────────────

  @Test
  void orderStatus_withTheApiKey_appliesTheStatusAndReturnsTheOrder() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    when(orderStatusService.apply("ord-1", "in_progress", "vhc-1", "2026-05-01T10:06:00Z"))
        .thenReturn(order);

    mockMvc
        .perform(statusRequest("test-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.id", is("ord-1")));
  }

  @Test
  void orderStatus_ignoresUnknownFields() throws Exception {
    when(orderStatusService.apply(anyString(), anyString(), any(), any())).thenReturn(new Order());

    mockMvc
        .perform(
            post("/infra/orders/ord-1/status")
                .header("X-Api-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"completed\",\"order_id\":\"ord-1\",\"extra\":1}"))
        .andExpect(status().isOk());

    verify(orderStatusService).apply(eq("ord-1"), eq("completed"), isNull(), isNull());
  }

  @Test
  void orderStatus_unknownOrder_returns404NotMaskedAs401() throws Exception {
    when(orderStatusService.apply(anyString(), anyString(), any(), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

    mockMvc
        .perform(statusRequest("test-key"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code", is("ORDER_NOT_FOUND")));
  }

  @Test
  void orderStatus_notAllowedTransition_returns409() throws Exception {
    when(orderStatusService.apply(anyString(), anyString(), any(), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "ORDER_NOT_MODIFIABLE"));

    mockMvc
        .perform(statusRequest("test-key"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code", is("ORDER_NOT_MODIFIABLE")));
  }

  @Test
  void orderStatus_missingVehicle_returns400() throws Exception {
    when(orderStatusService.apply(anyString(), anyString(), any(), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "VEHICLE_ID_REQUIRED"));

    mockMvc
        .perform(statusRequest("test-key"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", is("VEHICLE_ID_REQUIRED")));
  }

  @Test
  void orderStatus_malformedJson_returns400() throws Exception {
    mockMvc
        .perform(
            post("/infra/orders/ord-1/status")
                .header("X-Api-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(orderStatusService);
  }

  // ── POST /infra/vehicles/{id}/telemetry ─────────────────────────────────────

  @Test
  void telemetry_withTheApiKey_appliesTheReading() throws Exception {
    when(telemetryService.apply("vhc-1", "busy", 79, null, "2026-05-01T10:06:30Z"))
        .thenReturn("backend-uuid");

    mockMvc
        .perform(telemetryRequest("test-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vehicle_id", is("backend-uuid")));
  }

  @Test
  void telemetry_carriesTheErrorToTheService() throws Exception {
    when(telemetryService.apply(anyString(), any(), any(), any(), any()))
        .thenReturn("backend-uuid");

    mockMvc
        .perform(
            post("/infra/vehicles/vhc-1/telemetry")
                .header("X-Api-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"status\":\"error\",\"battery\":74,\"timestamp\":\"2026-05-01T10:07:00Z\","
                        + "\"error\":{\"code\":\"CONNECTION_LOST\",\"message\":\"Sin conexión\"}}"))
        .andExpect(status().isOk());

    verify(telemetryService)
        .apply(
            "vhc-1",
            "error",
            74,
            new VehicleTelemetryService.Fault("CONNECTION_LOST", "Sin conexión"),
            "2026-05-01T10:07:00Z");
  }

  @Test
  void telemetry_nullBatteryIsPassedAsNull() throws Exception {
    when(telemetryService.apply(anyString(), any(), any(), any(), any()))
        .thenReturn("backend-uuid");

    mockMvc
        .perform(
            post("/infra/vehicles/vhc-1/telemetry")
                .header("X-Api-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"idle\",\"battery\":null}"))
        .andExpect(status().isOk());

    verify(telemetryService).apply("vhc-1", "idle", null, null, null);
  }

  @Test
  void telemetry_malformedJson_returns400() throws Exception {
    mockMvc
        .perform(
            post("/infra/vehicles/vhc-1/telemetry")
                .header("X-Api-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
        .andExpect(status().isBadRequest());
    verify(telemetryService, never()).apply(any(), any(), any(), any(), any());
  }
}
