package com.usal.whbackend.service.console.stream;

/**
 * One subscription of one stream, as a source sees it. The first data frame it gets is the {@code
 * snapshot}; later ones are {@code update}s. Called by its source with the source's lock held.
 */
final class Subscriber {

  static final String INVALID_MESSAGE = "La suscripción no es válida.";
  static final String UNAVAILABLE_MESSAGE = "La fuente de datos no está disponible; se reintenta.";

  private final StreamSession session;
  private final String id;
  private boolean awaitingSnapshot = true;

  Subscriber(StreamSession session, String id) {
    this.session = session;
    this.id = id;
  }

  void data(boolean replace, String json) {
    boolean snapshot = awaitingSnapshot;
    awaitingSnapshot = false;
    session.offerData(id, snapshot, replace, json);
  }

  /**
   * The message is fixed per class of failure and never echoes the request or the store's reply, so
   * an error event cannot reveal whether a hidden metric exists.
   */
  void error(String code, boolean permanent) {
    session.offerError(id, code, permanent ? INVALID_MESSAGE : UNAVAILABLE_MESSAGE);
  }
}
