package com.usal.whbackend.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.usal.whbackend.service.exception.AccountDisabledException;
import com.usal.whbackend.service.exception.EmailAlreadyExistsException;
import com.usal.whbackend.service.exception.InvalidCredentialsException;
import com.usal.whbackend.service.exception.UserNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

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
}
