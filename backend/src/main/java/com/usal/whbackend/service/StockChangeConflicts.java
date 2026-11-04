package com.usal.whbackend.service;

import com.mongodb.MongoException;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns the transient write conflicts of concurrent stock changes into {@code 409
 * STOCK_CHANGE_CONFLICT}. Inside a MongoDB transaction a write to a document another in-flight
 * transaction already wrote fails at once with {@code WriteConflict} (code 112, label {@code
 * TransientTransactionError}), and the same error can surface when the transaction commits, which
 * happens outside the service method body. The caller of the transactional service method is
 * therefore the layer that sees every case. Nothing was changed by the losing call, so it can be
 * retried.
 */
public final class StockChangeConflicts {

  static final String CODE = "STOCK_CHANGE_CONFLICT";

  /** MongoDB's {@code WriteConflict} error code. */
  private static final int WRITE_CONFLICT = 112;

  private StockChangeConflicts() {}

  /** Runs a transactional stock change, answering 409 when it lost to a concurrent one. */
  public static <T> T translating(Supplier<T> stockChange) {
    try {
      return stockChange.get();
    } catch (RuntimeException e) {
      if (isTransientConflict(e)) {
        throw conflict(e);
      }
      throw e;
    }
  }

  public static ResponseStatusException conflict(Throwable cause) {
    return new ResponseStatusException(HttpStatus.CONFLICT, CODE, cause);
  }

  public static boolean isTransientConflict(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof TransientDataAccessException || t instanceof ConcurrencyFailureException) {
        return true;
      }
      if (t instanceof MongoException m
          && (m.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
              || m.getCode() == WRITE_CONFLICT)) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }
}
