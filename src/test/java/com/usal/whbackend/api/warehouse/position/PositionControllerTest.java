package com.usal.whbackend.api.warehouse.position;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.service.PositionService;
import com.usal.whbackend.service.exception.PositionAlreadyOccupiedException;
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

@WebMvcTest(PositionController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class PositionControllerTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean PositionService positionService;
  @MockitoBean ProductRepository productRepository;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private Position position(String id, String lineId) {
    Position p = new Position();
    p.setId(id);
    p.setIdLine(lineId);
    p.setIdZone("z1");
    p.setPositionName("P01");
    p.setActive(false);
    p.setMaximumCapacity(100);
    p.setSizeStockToSave(StockSize.MEDIO_PALLET);
    p.setCurrentStock(0);
    return p;
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getPositions_returns200() throws Exception {
    when(positionService.getPositionsByLine("l1")).thenReturn(List.of(position("p1", "l1")));
    mockMvc
        .perform(get("/warehouse/lines/l1/positions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions").isArray())
        .andExpect(jsonPath("$.positions[0].id_position").value("p1"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createPosition_valid_returns201() throws Exception {
    when(positionService.createPosition(eq("l1"), any())).thenReturn(position("p1", "l1"));
    mockMvc
        .perform(
            post("/warehouse/lines/l1/positions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"position_name\":\"P01\",\"maximum_capacity\":100,\"size_stock_to_save\":\"MEDIO_PALLET\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void updatePosition_occupied_returns409() throws Exception {
    when(positionService.updatePosition(eq("p1"), any()))
        .thenThrow(new PositionAlreadyOccupiedException("p1"));
    mockMvc
        .perform(
            patch("/warehouse/positions/p1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":\"other-product\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("POSITION_ALREADY_OCCUPIED"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void validateFit_whenFits_returns200AndFitsTrue() throws Exception {
    com.usal.whbackend.domain.Product prod = new com.usal.whbackend.domain.Product();
    prod.setId("product-1");
    prod.setHeight(10.0);
    prod.setWidth(10.0);
    prod.setLength(10.0); // 1000 cm3
    prod.setActive(true);
    when(productRepository.findById("product-1")).thenReturn(java.util.Optional.of(prod));

    mockMvc
        .perform(
            post("/warehouse/positions/validate-fit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":\"product-1\",\"quantity\":10,\"size\":\"PALLET\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fits").value(true))
        .andExpect(jsonPath("$.product_volume").value(1000.0))
        .andExpect(jsonPath("$.container_volume").value(1800000.0));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void validateFit_whenDoesNotFit_returns200AndFitsFalse() throws Exception {
    com.usal.whbackend.domain.Product prod = new com.usal.whbackend.domain.Product();
    prod.setId("product-1");
    prod.setHeight(200.0);
    prod.setWidth(200.0);
    prod.setLength(200.0); // 8000000 cm3
    prod.setActive(true);
    when(productRepository.findById("product-1")).thenReturn(java.util.Optional.of(prod));

    mockMvc
        .perform(
            post("/warehouse/positions/validate-fit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":\"product-1\",\"quantity\":1,\"size\":\"CAJA\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fits").value(false))
        .andExpect(jsonPath("$.product_volume").value(8000000.0))
        .andExpect(jsonPath("$.container_volume").value(48000.0));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void validateFit_whenProductNotFound_returns404() throws Exception {
    when(productRepository.findById("product-invalid")).thenReturn(java.util.Optional.empty());

    mockMvc
        .perform(
            post("/warehouse/positions/validate-fit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":\"product-invalid\",\"quantity\":1,\"size\":\"CAJA\"}"))
        .andExpect(status().isNotFound());
  }
}
