package com.usal.whbackend.api.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request body for creating a new product")
public record CreateProductRequest(
    @Schema(
            description = "Unique product identifier",
            example = "SKU-001",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String sku,
    @Schema(
            description = "Product display name",
            example = "Casco de seguridad",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
    @Schema(description = "Product description", example = "Casco homologado clase A")
        String description,
    @Schema(
            description = "Product category",
            example = "seguridad",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String category,
    @JsonProperty("image_url")
    @Schema(
            description = "Full URL of the product image",
            example = "https://example.com/images/casco.png")
        String imageUrl,
    @JsonProperty("available_stock")
    @Schema(description = "Units currently available in stock", example = "100")
        Integer availableStock,
    @JsonProperty("max_quantity_per_order")
    @Schema(description = "Maximum units a single order can request", example = "10")
        Integer maxQuantityPerOrder,
    @JsonProperty("minimum_stock")
    @Schema(description = "Minimum stock threshold that triggers a restock alert", example = "20")
        Integer minimumStock,
    @Schema(description = "Warehouse zone where the product is stored", example = "A") String zone,
    @Schema(description = "Aisle/line within the zone", example = "3") String line,
    @Schema(description = "Shelf position within the aisle", example = "B") String position,
    @Schema(description = "Height level on the shelf", example = "2") String height) {}
