package com.usal.whbackend.service.console.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Writes pre-rendered SSE frames to the response. A plain {@link ResponseBodyEmitter} (rather than
 * {@code SseEmitter}) so each frame goes out as exactly the bytes the stream renders, one write per
 * frame; the controller sets {@code Content-Type: text/event-stream}.
 */
final class EmitterSink implements FrameSink {

  private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

  private final ResponseBodyEmitter emitter;

  EmitterSink(ResponseBodyEmitter emitter) {
    this.emitter = emitter;
  }

  @Override
  public void send(String frame) throws IOException {
    emitter.send(frame, TEXT);
  }

  @Override
  public void complete() {
    try {
      emitter.complete();
    } catch (RuntimeException e) {
      // Already completed by the container.
    }
  }

  @Override
  public void onClose(Runnable callback) {
    emitter.onCompletion(callback);
    emitter.onTimeout(callback);
    emitter.onError(e -> callback.run());
  }
}
