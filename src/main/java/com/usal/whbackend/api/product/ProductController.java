package com.usal.whbackend.api.product;

import com.usal.whbackend.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
@Tag(name = "Products", description = "Product catalogue and stock management")
@SecurityRequirement(name = "bearer-jwt")
public class ProductController {

  private final ProductService productService;

  public ProductController(ProductService productService) {
    this.productService = productService;
  }

  @Operation(summary = "List products", description = "Returns active products by default, optionally filtered")
  @ApiResponse(responseCode = "200", description = "Product list")
  @GetMapping
  public ResponseEntity<Map<String, List<ProductResponse>>> getProducts(
      @Parameter(description = "Filter by category")
      @RequestParam(required = false) String category,
      @Parameter(description = "Search by name or SKU (case-insensitive)")
      @RequestParam(required = false) String search,
      @Parameter(description = "Filter by active status (default: true)")
      @RequestParam(required = false) Boolean isActive) {
    List<ProductResponse> products =
        productService.getProducts(category, search, isActive).stream()
            .map(ProductResponse::from)
            .toList();
    return ResponseEntity.ok(Map.of("products", products));
  }

  @Operation(summary = "Get product by ID")
  @ApiResponse(responseCode = "200", description = "Product found")
  @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
  @GetMapping("/{id}")
  public ResponseEntity<Map<String, ProductResponse>> getProduct(
      @PathVariable String id,
      @Parameter(description = "Include inactive products when false")
      @RequestParam(required = false) Boolean isActive) {
    return ResponseEntity.ok(
        Map.of("product", ProductResponse.from(productService.getProduct(id, isActive))));
  }

  @Operation(summary = "Create product", description = "Requires ADMIN_WAREHOUSE or ADMIN_SALES role")
  @ApiResponse(responseCode = "201", description = "Product created")
  @ApiResponse(responseCode = "400", description = "MISSING_REQUIRED_FIELDS or SKU_ALREADY_EXISTS")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasAnyRole('ADMIN_WAREHOUSE', 'ADMIN_SALES')")
  @PostMapping
  public ResponseEntity<Map<String, ProductResponse>> createProduct(
      @RequestBody CreateProductRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("product", ProductResponse.from(productService.createProduct(request))));
  }

  @Operation(summary = "Update product", description = "Partial update — only provided fields are changed. Requires ADMIN_WAREHOUSE or ADMIN_SALES role.")
  @ApiResponse(responseCode = "200", description = "Product updated")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
  @PreAuthorize("hasAnyRole('ADMIN_WAREHOUSE', 'ADMIN_SALES')")
  @PatchMapping("/{id}")
  public ResponseEntity<Map<String, ProductResponse>> updateProduct(
      @PathVariable String id, @RequestBody UpdateProductRequest request) {
    return ResponseEntity.ok(
        Map.of("product", ProductResponse.from(productService.updateProduct(id, request))));
  }

  @Operation(summary = "Delete product (soft delete)", description = "Sets active=false. Requires ADMIN_WAREHOUSE role.")
  @ApiResponse(responseCode = "204", description = "Product deleted")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
  @PreAuthorize("hasRole('ADMIN_WAREHOUSE')")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteProduct(@PathVariable String id) {
    productService.deleteProduct(id);
    return ResponseEntity.noContent().build();
  }
}
