// Package health exposes the liveness endpoint of the bridge.
package health

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"time"
)

const (
	shutdownTimeout = 5 * time.Second
	probeTimeout    = 2 * time.Second
)

// Handler answers 200 on GET /healthz for as long as the process is up.
func Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(
		w http.ResponseWriter,
		_ *http.Request,
	) {
		_, _ = w.Write([]byte("ok"))
	})
	return mux
}

// Health serves Handler on addr until ctx ends, then shuts down gracefully.
// It returns nil after a clean shutdown and the listen error otherwise.
func Health(
	ctx context.Context,
	addr string,
) error {
	server := &http.Server{Addr: addr, Handler: Handler(), ReadHeaderTimeout: probeTimeout}
	failed := make(chan error, 1)
	go func() { failed <- server.ListenAndServe() }()
	select {
	case err := <-failed:
		return err
	case <-ctx.Done():
		return shutdown(server)
	}
}

func shutdown(
	server *http.Server,
) error {
	ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	return server.Shutdown(ctx)
}

// Probe performs one GET /healthz against a server listening on addr and
// fails unless it answers 200. It backs the container health check, since
// the runtime image has no shell or curl.
func Probe(
	addr string,
) error {
	host := addr
	if strings.HasPrefix(addr, ":") {
		host = "127.0.0.1" + addr
	}
	client := &http.Client{Timeout: probeTimeout}
	resp, err := client.Get("http://" + host + "/healthz")
	if err != nil {
		return fmt.Errorf("probe %s: %w", host, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("probe %s: status %d", host, resp.StatusCode)
	}
	return nil
}
