package com.usal.whbackend.api.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

@Schema(description = "Request body for creating a new product")
public record CreateProductRequest(
    @NotBlank @Schema(description = "Unique product identifier", example = "SKU-001") String sku,
    @NotBlank @Schema(description = "Product display name", example = "Casco de seguridad")
        String name,
    @Schema(description = "Product description") String description,
    @NotBlank @Schema(description = "Product category", example = "seguridad") String category,
    @Schema(description = "Product images") List<ImageRequest> images,
    @Schema(description = "Product price") PriceRequest price,
    @Schema(description = "Technical spec list") List<SpecRequest> specs,
    @Min(0) @Schema(description = "Maximum units a single order can request")
        Integer maxQuantityPerOrder,
    @Min(0) @Schema(description = "Minimum stock threshold for restock alert")
        Integer minimumStock) {

  public CreateProductRequest {
    images = images == null ? null : List.copyOf(images);
    specs = specs == null ? null : List.copyOf(specs);
  }

  public record ImageRequest(String url, String alt, boolean isPrimary) {}

  public record PriceRequest(long amountCents, String currency, boolean taxIncluded) {}

  public record SpecRequest(String label, String value) {}
}
