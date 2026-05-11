package com.usal.whbackend.service;

import com.usal.whbackend.api.product.CreateProductRequest;
import com.usal.whbackend.api.product.UpdateProductRequest;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.ProductRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
    public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
                this.productRepository = productRepository;
    }

    public List<Product> getProducts(String category, String search, Boolean active) {
                List<Product> products;

                if (category != null) {
                                products = productRepository.findByCategory(category);
                } else if (active != null) {
                                products = productRepository.findByActive(active);
                } else {
                                products = productRepository.findAll();
                }

                if (category != null && active != null) {
                                final boolean activeFilter = active;
                                products = products.stream()
                                                        .filter(p -> p.isActive() == activeFilter)
                                                        .toList();
                }

                if (search != null && !search.isBlank()) {
                                final String lowerSearch = search.toLowerCase();
                                products = products.stream()
                                                        .filter(p -> (p.getName() != null && p.getName().toLowerCase().contains(lowerSearch))
                                                                                            || (p.getSku() != null && p.getSku().toLowerCase().contains(lowerSearch)))
                                                        .toList();
                }

                return products;
    }

    public Product getProduct(String id) {
                return productRepository.findById(id)
                                    .orElseThrow(() -> new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    }

    public Product createProduct(CreateProductRequest request) {
                if (request.sku() == null || request.sku().isBlank()
                                    || request.name() == null || request.name().isBlank()
                                    || request.category() == null || request.category().isBlank()) {
                                throw new ResponseStatusException(
                                                        HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_FIELDS");
                }

                if (productRepository.findBySku(request.sku()).isPresent()) {
                                throw new ResponseStatusException(
                                                        HttpStatus.BAD_REQUEST, "SKU_ALREADY_EXISTS");
                }

                Product product = new Product();
                product.setSku(request.sku());
                product.setName(request.name());
                product.setDescription(request.description());
                product.setCategory(request.category());
                product.setImageUrl(request.imageUrl());
                product.setActive(true);
                product.setAvailableStock(0);
                product.setReservedStock(0);
                product.setCreatedAt(Instant.now());

                return productRepository.save(product);
    }

    public Product updateProduct(String id, UpdateProductRequest request) {
                Product product = productRepository.findById(id)
                                    .orElseThrow(() -> new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

                if (request.name() != null) {
                                product.setName(request.name());
                }
                if (request.description() != null) {
                                product.setDescription(request.description());
                }
                if (request.category() != null) {
                                product.setCategory(request.category());
                }
                if (request.imageUrl() != null) {
                                product.setImageUrl(request.imageUrl());
                }

                return productRepository.save(product);
    }

    public void deleteProduct(String id) {
                Product product = productRepository.findById(id)
                                    .orElseThrow(() -> new ResponseStatusException(
                                                                HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

                product.setActive(false);
                productRepository.save(product);
    }
    }
