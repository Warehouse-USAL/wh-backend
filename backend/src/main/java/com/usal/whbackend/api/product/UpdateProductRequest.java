package com.usal.whbackend.api.product;

import jakarta.validation.constraints.Min;
import java.util.List;

public record UpdateProductRequest(
    String name,
    String description,
    String category,
    List<CreateProductRequest.ImageRequest> images,
    CreateProductRequest.PriceRequest price,
    List<CreateProductRequest.SpecRequest> specs,
    @Min(0) Integer maxQuantityPerOrder,
    @Min(0) Integer minimumStock,
    Boolean isActive,
    @Min(0) Double height,
    @Min(0) Double width,
    @Min(0) Double length,
    @Min(0) Double weight) {

  public UpdateProductRequest {
    images = images == null ? null : List.copyOf(images);
    specs = specs == null ? null : List.copyOf(specs);
  }
}
