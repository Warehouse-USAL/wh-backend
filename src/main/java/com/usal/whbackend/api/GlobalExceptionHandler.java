package com.usal.whbackend.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
  public class GlobalExceptionHandler {

    private static final Map<String, String> MESSAGES = Map.ofEntries(
                  Map.entry("ORDER_NOT_FOUND", "La orden solicitada no existe."),
                  Map.entry("ORDER_NOT_CANCELLABLE", "La orden no puede cancelarse en su estado actual."),
                  Map.entry("PRODUCT_NOT_FOUND", "El producto solicitado no existe."),
                  Map.entry("PRODUCT_INACTIVE", "El producto no está disponible en el catálogo."),
                  Map.entry("INSUFFICIENT_STOCK", "No hay stock suficiente para uno o más productos."),
                  Map.entry("QUANTITY_EXCEEDS_LIMIT", "La cantidad solicitada supera el máximo permitido por orden."),
                  Map.entry("INVALID_QUANTITY", "La cantidad de cada producto debe ser mayor a cero."),
                  Map.entry("DESTINATION_AREA_REQUIRED", "El área de destino es obligatoria."),
                  Map.entry("ITEMS_REQUIRED", "La orden debe contener al menos un producto."),
                  Map.entry("INVALID_STATUS", "Estado inválido. Valores aceptados: PENDING, IN_PROGRESS, COMPLETED, CANCELLED."),
                  Map.entry("INVALID_DATE_FORMAT", "Formato de fecha inválido. Usar ISO-8601, ej: 2024-01-01T00:00:00Z."),
                  Map.entry("DUPLICATE_PRODUCT_IN_ORDER", "No se puede incluir el mismo producto más de una vez en la orden."),
                  Map.entry("MISSING_REQUIRED_FIELDS", "Los campos sku, name y category son obligatorios."),
                  Map.entry("SKU_ALREADY_EXISTS", "Ya existe un producto con ese SKU.")
          );

    @ExceptionHandler(ResponseStatusException.class)
        public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex) {
                  String code = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
                  String message = MESSAGES.getOrDefault(code, code);
                  return ResponseEntity.status(ex.getStatusCode())
                                    .body(Map.of("error", Map.of("code", code, "message", message)));
        }
  }
