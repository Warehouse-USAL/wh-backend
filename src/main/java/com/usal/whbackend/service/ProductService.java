package com.usal.whbackend.service;

import com.usal.whbackend.api.product.CreateProductRequest;
import com.usal.whbackend.api.product.UpdateProductRequest;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<Product> getProducts(String category, String search, Boolean active) {
        throw new UnsupportedOperationException("not implemented");
    }

    public Product getProduct(String id) {
        throw new UnsupportedOperationException("not implemented");
    }

    public Product createProduct(CreateProductRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    public Product updateProduct(String id, UpdateProductRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void deleteProduct(String id) {
        throw new UnsupportedOperationException("not implemented");
    }
}
