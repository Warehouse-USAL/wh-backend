package com.usal.whbackend.api.restock.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * {@code expectedDeliveryDate} (#134) is optional and purely informational — if provided it must be
 * strictly after the server-side {@code createdAt}, otherwise the service rejects it with {@code
 * INVALID_EXPECTED_DELIVERY_DATE}.
 */
public record CreateRestockOrderRequest(
    @NotBlank String productId,
    @Min(1) int quantityRequested,
    @NotBlank String supplier,
    Instant expectedDeliveryDate) {}
