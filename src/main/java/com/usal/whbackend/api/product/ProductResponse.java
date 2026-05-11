package com.usal.whbackend.api.product;

import com.usal.whbackend.domain.Product;

public record ProductResponse(
                String id,
                String sku,
                String name,
                String description,
                String category,
                String imageUrl,
                int availableStock,
                int reservedStock,
                boolean active) {

            public static ProductResponse from(Product product) {
                            return new ProductResponse(
                                    product.getId(),
                                    product.getSku(),
                                    product.getName(),
                                    product.getDescription(),
                                    product.getCategory(),
                                    product.getImageUrl(),
                                    product.getAvailableStock(),
                                    product.getReservedStock(),
                                    product.isActive());
}
}
