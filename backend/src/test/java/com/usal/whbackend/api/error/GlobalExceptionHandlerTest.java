package com.usal.whbackend.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.usal.whbackend.service.exception.AccountDisabledException;
import com.usal.whbackend.service.exception.EmailAlreadyExistsException;
import com.usal.whbackend.service.exception.InvalidCredentialsException;
import com.usal.whbackend.service.exception.LineNumberAlreadyExistsException;
import com.usal.whbackend.service.exception.PositionNotFoundException;
import com.usal.whbackend.service.exception.StockExceedsCapacityException;
import com.usal.whbackend.service.exception.UserNotFoundException;
import com.usal.whbackend.service.storage.FileNotFoundException;
import com.usal.whbackend.service.storage.StorageException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void invalidCredentials_returns401WithCode() {
    ResponseEntity<ErrorResponse> r =
        handler.handleInvalidCredentials(new InvalidCredentialsException());
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(r.getBody().error().code()).isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  void accountDisabled_returns403WithCode() {
    ResponseEntity<ErrorResponse> r = handler.handleAccountDisabled(new AccountDisabledException());
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(r.getBody().error().code()).isEqualTo("ACCOUNT_DISABLED");
  }

  @Test
  void userNotFound_returns404WithCode() {
    ResponseEntity<ErrorResponse> r = handler.handleUserNotFound(new UserNotFoundException("123"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(r.getBody().error().code()).isEqualTo("USER_NOT_FOUND");
  }

  @Test
  void emailAlreadyExists_returns409WithCode() {
    ResponseEntity<ErrorResponse> r =
        handler.handleEmailAlreadyExists(new EmailAlreadyExistsException("x@x.com"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(r.getBody().error().code()).isEqualTo("EMAIL_ALREADY_EXISTS");
  }

  @Test
  void malformedRequest_returns400WithCode() {
    ResponseEntity<ErrorResponse> r =
        handler.handleMalformedRequest(mock(HttpMessageNotReadableException.class));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(r.getBody()).isNotNull();
    assertThat(r.getBody().error().code()).isEqualTo("MALFORMED_REQUEST");
  }

  @Test
  void validationError_returns400WithCode() throws Exception {
    MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
    BindingResult bindingResult = mock(BindingResult.class);
    FieldError fieldError = new FieldError("obj", "field", "must not be null");
    when(ex.getBindingResult()).thenReturn(bindingResult);
    when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

    ResponseEntity<ErrorResponse> r = handler.handleValidation(ex);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(r.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void lineNumberAlreadyExists_returns409WithCode() {
    ResponseEntity<ErrorResponse> r =
        handler.handleLineNumberExists(new LineNumberAlreadyExistsException(1, "z1"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(r.getBody().error().code()).isEqualTo("LINE_NUMBER_ALREADY_EXISTS");
  }

  @Test
  void positionNotFound_returns404WithCode() {
    ResponseEntity<ErrorResponse> r =
        handler.handlePositionNotFound(new PositionNotFoundException("p1"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(r.getBody().error().code()).isEqualTo("POSITION_NOT_FOUND");
    assertThat(r.getBody().error().message()).contains("p1");
  }

  @Test
  void stockExceedsCapacity_returns400WithCode() {
    ResponseEntity<ErrorResponse> r =
        handler.handleStockExceedsCapacity(new StockExceedsCapacityException(500, 100));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(r.getBody().error().code()).isEqualTo("STOCK_EXCEEDS_CAPACITY");
  }

  @Test
  void fileNotFound_returns404WithCode() {
    ResponseEntity<ErrorResponse> r = handler.handleFileNotFound(new FileNotFoundException("k"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(r.getBody().error().code()).isEqualTo("FILE_NOT_FOUND");
  }

  @Test
  void storageError_returns500WithCode() {
    ResponseEntity<ErrorResponse> r =
        handler.handleStorage(new StorageException("boom", new IllegalStateException("cause")));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(r.getBody().error().code()).isEqualTo("STORAGE_ERROR");
  }

  @Test
  void maxUploadSizeExceeded_returns413WithCode() {
    ResponseEntity<ErrorResponse> r =
        handler.handleMaxUploadSize(new MaxUploadSizeExceededException(5L));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(r.getBody().error().code()).isEqualTo("PAYLOAD_TOO_LARGE");
  }

  @Test
  void responseStatusException_withoutReason_fallsBackToStatusCode() {
    ResponseEntity<java.util.Map<String, Object>> r =
        handler.handle(new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.I_AM_A_TEAPOT);
    assertThat(r.getBody()).isNotNull();
    assertThat(r.getBody().get("error")).isNotNull();
  }

  @Test
  void responseStatusException_knownReason_mapsToFriendlyMessage() {
    ResponseEntity<java.util.Map<String, Object>> r =
        handler.handle(new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> error = (java.util.Map<String, Object>) r.getBody().get("error");
    assertThat(error.get("code")).isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  void accessDenied_returns403WithCode() {
    ResponseEntity<ErrorResponse> r =
        handler.handleAccessDenied(
            new org.springframework.security.access.AccessDeniedException("nope"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(r.getBody().error().code()).isEqualTo("ACCESS_DENIED");
  }
}
