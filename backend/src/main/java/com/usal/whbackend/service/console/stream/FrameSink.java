package com.usal.whbackend.service.console.stream;

import java.io.IOException;

/** Where a stream's frames go: the HTTP response in production, a recorder in tests. */
interface FrameSink {

  /** Writes one complete SSE frame; may block while the client is slow. */
  void send(String frame) throws IOException;

  /** Ends the response. Must not throw. */
  void complete();

  /** Registers what to run when the container ends the response (client gone, timeout, error). */
  void onClose(Runnable callback);
}
