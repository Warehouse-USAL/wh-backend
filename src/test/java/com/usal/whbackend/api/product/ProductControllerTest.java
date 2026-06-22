package com.usal.whbackend.api.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.service.ProductService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean ProductService productService;
  @MockitoBean JwtService jwtService;

  private ProductResponse sampleProductResponse;

  @BeforeEach
  void setUp() {
    sampleProductResponse =
        new ProductResponse(
            "prod-1",
            "SKU-001",
            "Test Product",
            null,
            "TECNOLOGIA",
            List.of(),
            null,
            List.of(),
            new ProductResponse.Stock(10, 0, 0),
            new ProductResponse.OrderConstraints(0),
            true,
            null,
            10.0,
            10.0,
            10.0,
            1.0,
            1000.0);
  }

  @Test
  @WithMockUser
  void getProducts_returns200() throws Exception {
    Pageable pageable = PageRequest.of(0, 10);
    when(productService.getProducts(any(), any(), any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(java.util.List.of(sampleProductResponse), pageable, 1));
    mockMvc
        .perform(get("/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.products").isArray())
        .andExpect(jsonPath("$.pagination.total_elements").value(1))
        .andExpect(jsonPath("$.pagination.page").value(0))
        .andExpect(jsonPath("$.pagination.size").value(10))
        .andExpect(jsonPath("$.pagination.total_pages").value(1));
  }

  @Test
  @WithMockUser
  void getProduct_returns200() throws Exception {
    when(productService.getProduct(anyString(), any())).thenReturn(sampleProductResponse);
    mockMvc
        .perform(get("/products/prod-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.product.sku").value("SKU-001"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createProduct_returns201() throws Exception {
    when(productService.createProduct(any())).thenReturn(sampleProductResponse);
    mockMvc
        .perform(
            post("/products")
                .contentType("application/json")
                .content("{\"sku\":\"SKU-001\",\"name\":\"Test\",\"category\":\"TECNOLOGIA\",\"height\":10.0,\"width\":10.0,\"length\":10.0,\"weight\":1.0}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.product").exists())
        .andExpect(jsonPath("$.product.sku").value("SKU-001"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void updateProduct_returns200() throws Exception {
    when(productService.updateProduct(anyString(), any())).thenReturn(sampleProductResponse);
    mockMvc
        .perform(
            patch("/products/prod-1")
                .contentType("application/json")
                .content("{\"name\":\"Updated\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.product").exists());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void deleteProduct_returns204() throws Exception {
    doNothing().when(productService).deleteProduct(anyString());
    mockMvc.perform(delete("/products/prod-1")).andExpect(status().isNoContent());
  }

  @Test
  @WithMockUser
  void getProduct_responseBodyUsesSnakeCaseKeys() throws Exception {
    // Regression: API must serialize responses with snake_case field names so that
    // consumers sending image_url / available_stock / max_quantity_per_order / minimum_stock
    // receive them back under the same snake_case keys.
    ProductResponse product =
        new ProductResponse(
            "prod-snake",
            "SKU-SNAKE",
            "Snake Product",
            null,
            "TECNOLOGIA",
            List.of(new ProductResponse.Image("https://example.com/img.png", "Front", true)),
            new ProductResponse.Price(4999900L, "ARS", false),
            List.of(new ProductResponse.Spec("Peso", "250 g")),
            new ProductResponse.Stock(50, 0, 10),
            new ProductResponse.OrderConstraints(5),
            true,
            Instant.parse("2026-01-01T00:00:00Z"),
            10.0,
            10.0,
            10.0,
            1.0,
            1000.0);

    when(productService.getProduct(anyString(), any())).thenReturn(product);

    mockMvc
        .perform(get("/products/prod-snake"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.product.images").isArray())
        .andExpect(jsonPath("$.product.images[0].url").value("https://example.com/img.png"))
        .andExpect(jsonPath("$.product.images[0].alt").value("Front"))
        .andExpect(jsonPath("$.product.images[0].is_primary").value(true))
        .andExpect(jsonPath("$.product.price.amount_cents").value(4999900))
        .andExpect(jsonPath("$.product.price.currency").value("ARS"))
        .andExpect(jsonPath("$.product.price.tax_included").value(false))
        .andExpect(jsonPath("$.product.specs").isArray())
        .andExpect(jsonPath("$.product.specs[0].label").value("Peso"))
        .andExpect(jsonPath("$.product.specs[0].value").value("250 g"))
        .andExpect(jsonPath("$.product.created_at").exists())
        .andExpect(jsonPath("$.product.stock.min").value(10))
        .andExpect(jsonPath("$.product.order_constraints.max_quantity_per_order").value(5))
        .andExpect(jsonPath("$.product.stock.available").value(50));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createProduct_acceptsSnakeCaseRequestBody() throws Exception {
    // Regression: fields sent as snake_case must be deserialized, not silently dropped.
    ProductResponse stored =
        new ProductResponse(
            "prod-sc",
            "SKU-SC",
            "Snake Create",
            null,
            "HERRAMIENTAS",
            List.of(new ProductResponse.Image("https://example.com/tool.png", null, true)),
            new ProductResponse.Price(1500000L, "ARS", false),
            List.of(new ProductResponse.Spec("Marca", "Acme")),
            new ProductResponse.Stock(100, 0, 20),
            new ProductResponse.OrderConstraints(10),
            true,
            Instant.parse("2026-01-01T00:00:00Z"),
            10.0,
            10.0,
            10.0,
            1.0,
            1000.0);

    ArgumentCaptor<CreateProductRequest> captor =
        ArgumentCaptor.forClass(CreateProductRequest.class);
    when(productService.createProduct(captor.capture())).thenReturn(stored);

    mockMvc
        .perform(
            post("/products")
                .contentType("application/json")
                .content(
                    "{\"sku\":\"SKU-SC\",\"name\":\"Snake Create\",\"category\":\"HERRAMIENTAS\","
                        + "\"images\":[{\"url\":\"https://example.com/tool.png\",\"alt\":null,"
                        + "\"is_primary\":true}],"
                        + "\"price\":{\"amount_cents\":1500000,\"currency\":\"ARS\","
                        + "\"tax_included\":false},"
                        + "\"specs\":[{\"label\":\"Marca\",\"value\":\"Acme\"}],"
                        + "\"max_quantity_per_order\":10,"
                        + "\"minimum_stock\":20,"
                        + "\"height\":10.0,\"width\":10.0,\"length\":10.0,\"weight\":1.0}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.product.images[0].url").value("https://example.com/tool.png"))
        .andExpect(jsonPath("$.product.price.amount_cents").value(1500000))
        .andExpect(jsonPath("$.product.price.tax_included").value(false))
        .andExpect(jsonPath("$.product.specs[0].label").value("Marca"))
        .andExpect(jsonPath("$.product.stock.available").value(100))
        .andExpect(jsonPath("$.product.order_constraints.max_quantity_per_order").value(10));

    // Verify Jackson actually deserialized the snake_case fields (not silently dropped)
    CreateProductRequest captured = captor.getValue();
    assertNotNull(captured.images());
    assertEquals(1, captured.images().size());
    assertTrue(captured.images().get(0).isPrimary(), "is_primary must deserialize to true");
    assertNotNull(captured.price());
    assertEquals(1500000L, captured.price().amountCents());
    assertFalse(captured.price().taxIncluded(), "tax_included must deserialize to false");
    assertNotNull(captured.specs());
    assertEquals(1, captured.specs().size());
    assertEquals("Marca", captured.specs().get(0).label());
  }

  @Test
  @WithMockUser
  void getProducts_sizeExceedsMax_clampsTo50() throws Exception {
    when(productService.getProducts(any(), any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(3);
              return new PageImpl<>(java.util.List.of(), p, 0);
            });
    mockMvc
        .perform(get("/products").param("size", "200"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.size").value(50));
  }

  @Test
  @WithMockUser
  void getProducts_explicitPage_passedThrough() throws Exception {
    when(productService.getProducts(any(), any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(3);
              return new PageImpl<>(java.util.List.of(sampleProductResponse), p, 25);
            });
    mockMvc
        .perform(get("/products").param("page", "1").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.page").value(1))
        .andExpect(jsonPath("$.pagination.total_pages").value(3));
  }

  @Test
  @WithMockUser
  void getProducts_negativePage_clampsToZero() throws Exception {
    when(productService.getProducts(any(), any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(3);
              return new PageImpl<>(java.util.List.of(), p, 0);
            });
    mockMvc
        .perform(get("/products").param("page", "-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.page").value(0));
  }

  @Test
  @WithMockUser
  void getProducts_zeroSize_clampsToOne() throws Exception {
    when(productService.getProducts(any(), any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(3);
              return new PageImpl<>(java.util.List.of(), p, 0);
            });
    mockMvc
        .perform(get("/products").param("size", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.size").value(1));
  }

  @Test
  @WithMockUser
  void getProducts_invalidCategory_returns400() throws Exception {
    when(productService.getProducts(org.mockito.ArgumentMatchers.eq("INVALID_CAT"), any(), any(), any(Pageable.class)))
        .thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_CATEGORY"));

    mockMvc
        .perform(get("/products").param("category", "INVALID_CAT"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_CATEGORY"))
        .andExpect(jsonPath("$.error.message").value("La categoría indicada no existe."));
  }
}
