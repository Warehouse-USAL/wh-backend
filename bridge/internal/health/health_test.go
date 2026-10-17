package health_test

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/health"
)

func TestHandlerAnswersOKOnHealthz(
	t *testing.T,
) {
	server := httptest.NewServer(health.Handler())
	t.Cleanup(server.Close)
	resp, err := http.Get(server.URL + "/healthz")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", resp.StatusCode)
	}
}

func TestProbeSucceedsAgainstARunningServer(
	t *testing.T,
) {
	server := httptest.NewServer(health.Handler())
	t.Cleanup(server.Close)
	addr := server.Listener.Addr().String()
	if err := health.Probe(addr); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestProbeFailsWhenNothingListens(
	t *testing.T,
) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := listener.Addr().String()
	listener.Close()
	if err := health.Probe(addr); err == nil {
		t.Fatal("expected an error")
	}
}

func TestHealthServesUntilContextEnds(
	t *testing.T,
) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := listener.Addr().String()
	listener.Close()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- health.Health(ctx, addr) }()
	waitUntilUp(t, addr)
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Health returned %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Health did not stop")
	}
}

func waitUntilUp(
	t *testing.T,
	addr string,
) {
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if health.Probe(addr) == nil {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("server never came up")
}
