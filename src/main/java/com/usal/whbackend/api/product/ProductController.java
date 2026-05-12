package com.usal.whbackend.api.product;

import com.usal.whbackend.service.ProductService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
                this.productService = productService;
    }

    @GetMapping
            public ResponseEntity<Map<String, List<ProductResponse>>> getProducts(
                            @RequestParam(required = false) String category,
                            @RequestParam(required = false) String search,
                            @RequestParam(required = false) Boolean isActive) {
                        List<ProductResponse> products = productService.getProducts(category, search, isActive)
                                            .stream()
                                            .map(ProductResponse::from)
                                            .toList();
                        return ResponseEntity.ok(Map.of("products", products));
            }

    @GetMapping("/{id}")
            public ResponseEntity<Map<String, ProductResponse>> getProduct(
                            @PathVariable String id,
                            @RequestParam(required = false) Boolean isActive) {
                        return ResponseEntity.ok(Map.of("product", ProductResponse.from(productService.getProduct(id, isActive))));
            }

    @PostMapping
            public ResponseEntity<Map<String, ProductResponse>> createProduct(
                            @RequestBody CreateProductRequest request) {
                        return ResponseEntity.status(HttpStatus.CREATED)
                                            .body(Map.of("product", ProductResponse.from(productService.createProduct(request))));
            }

    @PatchMapping("/{id}")
            public ResponseEntity<Map<String, ProductResponse>> updateProduct(
                            @PathVariable String id, @RequestBody UpdateProductRequest request) {
                        return ResponseEntity.ok(
                                            Map.of("product", ProductResponse.from(productService.updateProduct(id, request))));
            }

    @DeleteMapping("/{id}")
            public ResponseEntity<Void> deleteProduct(@PathVariable String id) {
                        productService.deleteProduct(id);
                        return ResponseEntity.noContent().build();
            }
    }
