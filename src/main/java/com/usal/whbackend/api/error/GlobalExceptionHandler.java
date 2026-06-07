package com.usal.whbackend.api.error;

import com.usal.whbackend.service.exception.AccountDisabledException;
import com.usal.whbackend.service.exception.EmailAlreadyExistsException;
import com.usal.whbackend.service.exception.InvalidCredentialsException;
import com.usal.whbackend.service.exception.LineNotFoundException;
import com.usal.whbackend.service.exception.LineNumberAlreadyExistsException;
import com.usal.whbackend.service.exception.PositionAlreadyOccupiedException;
import com.usal.whbackend.service.exception.PositionNotFoundException;
import com.usal.whbackend.service.exception.StockExceedsCapacityException;
import com.usal.whbackend.service.exception.UserNotFoundException;
import com.usal.whbackend.service.exception.ZoneCodeAlreadyExistsException;
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final Map<String, String> MESSAGES =
      Map.ofEntries(
          Map.entry("ORDER_NOT_FOUND", "La orden solicitada no existe."),
          Map.entry("ORDER_NOT_CANCELLABLE", "La orden no puede cancelarse en su estado actual."),
          Map.entry("PRODUCT_NOT_FOUND", "Uno o más productos no existen en el catálogo."),
          Map.entry("PRODUCT_INACTIVE", "Uno o más productos no están disponibles en el catálogo."),
          Map.entry("INSUFFICIENT_STOCK", "No hay stock suficiente para uno o más productos."),
          Map.entry(
              "QUANTITY_EXCEEDS_LIMIT",
              "La cantidad solicitada supera el máximo permitido por orden."),
          Map.entry("INVALID_QUANTITY", "La cantidad de cada producto debe ser mayor a cero."),
          Map.entry("DESTINATION_AREA_REQUIRED", "El área de destino es obligatoria."),
          Map.entry("ITEMS_REQUIRED", "La orden debe contener al menos un producto."),
          Map.entry(
              "INVALID_STATUS",
              "Estado inválido. Valores aceptados: PENDING, IN_PROGRESS, COMPLETED, CANCELLED."),
          Map.entry(
              "INVALID_DATE_FORMAT",
              "Formato de fecha inválido. Usar ISO-8601, ej: 2024-01-01T00:00:00Z."),
          Map.entry(
              "DUPLICATE_PRODUCT_IN_ORDER",
              "No se puede incluir el mismo producto más de una vez en la orden."),
          Map.entry("MISSING_REQUIRED_FIELDS", "Los campos sku, name y category son obligatorios."),
          Map.entry("SKU_ALREADY_EXISTS", "Ya existe un producto con ese SKU."),
          Map.entry("ZONE_INACTIVE", "La zona no está activa y no puede recibir nuevas líneas."),
          Map.entry(
              "LINE_INACTIVE", "La línea no está activa y no puede recibir nuevas posiciones."),
          Map.entry("MISSING_ADDRESS", "La dirección de entrega es obligatoria."),
          Map.entry("MISSING_ADDRESS_STREET", "El campo calle (street) es obligatorio."),
          Map.entry(
              "MISSING_ADDRESS_POSTAL_CODE",
              "El campo código postal (postal_code) es obligatorio."));

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ErrorResponse.of("ACCESS_DENIED", "No tiene permisos para realizar esta operación."));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex) {
    String code = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
    String message = MESSAGES.getOrDefault(code, code);
    return ResponseEntity.status(ex.getStatusCode())
        .body(Map.of("error", Map.of("code", code, "message", message)));
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ErrorResponse.of("INVALID_CREDENTIALS", ex.getMessage()));
  }

  @ExceptionHandler(AccountDisabledException.class)
  public ResponseEntity<ErrorResponse> handleAccountDisabled(AccountDisabledException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ErrorResponse.of("ACCOUNT_DISABLED", ex.getMessage()));
  }

  @ExceptionHandler(UserNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of("USER_NOT_FOUND", ex.getMessage()));
  }

  @ExceptionHandler(EmailAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("EMAIL_ALREADY_EXISTS", ex.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("BAD_REQUEST", ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .findFirst()
            .orElse("Validation error");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("VALIDATION_ERROR", message));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleMalformedRequest(HttpMessageNotReadableException ex) {
    log.warn("Malformed request body: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ErrorResponse.of("MALFORMED_REQUEST", "El cuerpo de la solicitud no es JSON válido."));
  }

  @ExceptionHandler(ZoneNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleZoneNotFound(ZoneNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of("ZONE_NOT_FOUND", ex.getMessage()));
  }

  @ExceptionHandler(ZoneCodeAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleZoneCodeExists(ZoneCodeAlreadyExistsException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("ZONE_CODE_ALREADY_EXISTS", ex.getMessage()));
  }

  @ExceptionHandler(LineNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleLineNotFound(LineNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of("LINE_NOT_FOUND", ex.getMessage()));
  }

  @ExceptionHandler(LineNumberAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleLineNumberExists(LineNumberAlreadyExistsException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("LINE_NUMBER_ALREADY_EXISTS", ex.getMessage()));
  }

  @ExceptionHandler(PositionNotFoundException.class)
  public ResponseEntity<ErrorResponse> handlePositionNotFound(PositionNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of("POSITION_NOT_FOUND", ex.getMessage()));
  }

  @ExceptionHandler(PositionAlreadyOccupiedException.class)
  public ResponseEntity<ErrorResponse> handlePositionOccupied(PositionAlreadyOccupiedException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("POSITION_ALREADY_OCCUPIED", ex.getMessage()));
  }

  @ExceptionHandler(StockExceedsCapacityException.class)
  public ResponseEntity<ErrorResponse> handleStockExceedsCapacity(
      StockExceedsCapacityException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("STOCK_EXCEEDS_CAPACITY", ex.getMessage()));
  }
}
