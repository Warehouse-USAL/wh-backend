package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.usal.whbackend.api.product.CreateProductRequest;
import com.usal.whbackend.api.product.UpdateProductRequest;
import com.usal.whbackend.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

  @Mock ProductRepository productRepository;
  @InjectMocks ProductService productService;

  @Test
  void getProducts_throwsUnsupported() {
    assertThrows(
        UnsupportedOperationException.class, () -> productService.getProducts(null, null, null));
  }

  @Test
  void getProduct_throwsUnsupported() {
    assertThrows(UnsupportedOperationException.class, () -> productService.getProduct("id-1"));
  }

  @Test
  void createProduct_throwsUnsupported() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> productService.createProduct(new CreateProductRequest(null, null, null, null, null)));
  }

  @Test
  void updateProduct_throwsUnsupported() {
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            productService.updateProduct("id-1", new UpdateProductRequest(null, null, null, null)));
  }

  @Test
  void deleteProduct_throwsUnsupported() {
    assertThrows(UnsupportedOperationException.class, () -> productService.deleteProduct("id-1"));
  }
}
