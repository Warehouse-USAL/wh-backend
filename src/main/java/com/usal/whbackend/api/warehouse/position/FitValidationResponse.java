package com.usal.whbackend.api.warehouse.position;

public record FitValidationResponse(
    boolean fits,
    double productVolume,
    double containerVolume,
    double requiredVolume,
    int maxQuantityAllowed
) {}
