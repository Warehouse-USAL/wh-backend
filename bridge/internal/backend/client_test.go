package backend_test

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/backend"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/classify"
)

type recorded struct {
	method string
	path   string
	key    string
	ctype  string
	body   string
}

type fakeBackend struct {
	status   int
	requests []recorded
}

func (f *fakeBackend) ServeHTTP(
	w http.ResponseWriter,
	r *http.Request,
) {
	body, _ := io.ReadAll(r.Body)
	f.requests = append(f.requests, recorded{
		method: r.Method,
		path:   r.URL.EscapedPath(),
		key:    r.Header.Get("X-Api-Key"),
		ctype:  r.Header.Get("Content-Type"),
		body:   string(body),
	})
	w.WriteHeader(f.status)
	_, _ = w.Write([]byte("reply"))
}

var sample = classify.Request{
	Path: "/infra/orders/A%2FB/status",
	Body: []byte(`{"status":"completed"}`),
	Kind: classify.KindOrderStatus,
}

func newClient(
	t *testing.T,
	status int,
) (backend.Client, *fakeBackend) {
	fake := &fakeBackend{status: status}
	server := httptest.NewServer(fake)
	t.Cleanup(server.Close)
	return backend.New(server.URL+"/", "secret", time.Second), fake
}

func TestSendPostsJSONWithAPIKey(
	t *testing.T,
) {
	client, fake := newClient(t, http.StatusOK)
	if err := client.Send(context.Background(), sample); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	got := fake.requests[0]
	want := recorded{
		method: http.MethodPost,
		path:   "/infra/orders/A%2FB/status",
		key:    "secret",
		ctype:  "application/json",
		body:   `{"status":"completed"}`,
	}
	if got != want {
		t.Fatalf("got %+v, want %+v", got, want)
	}
}

type statusCase struct {
	status   int
	rejected bool
}

func (c statusCase) run(
	t *testing.T,
) {
	client, _ := newClient(t, c.status)
	err := client.Send(context.Background(), sample)
	if err == nil {
		t.Fatalf("status %d: expected an error", c.status)
	}
	if errors.Is(err, backend.ErrRejected) != c.rejected {
		t.Fatalf("status %d: rejected=%v, got error %v", c.status, c.rejected, err)
	}
}

func TestSendClassifiesStatuses(
	t *testing.T,
) {
	cases := []statusCase{
		{status: 400, rejected: true},
		{status: 401, rejected: true},
		{status: 404, rejected: true},
		{status: 409, rejected: true},
		{status: 408, rejected: false},
		{status: 429, rejected: false},
		{status: 500, rejected: false},
		{status: 503, rejected: false},
	}
	for _, c := range cases {
		t.Run(http.StatusText(c.status), c.run)
	}
}

func TestSendRejectedErrorCarriesStatusAndBody(
	t *testing.T,
) {
	client, _ := newClient(t, http.StatusConflict)
	err := client.Send(context.Background(), sample)
	var rejected *backend.RejectedError
	if !errors.As(err, &rejected) {
		t.Fatalf("got %v, want *RejectedError", err)
	}
	if rejected.Status != http.StatusConflict || rejected.Body != "reply" {
		t.Fatalf("got %+v", rejected)
	}
}

func TestSendNetworkErrorIsTransient(
	t *testing.T,
) {
	server := httptest.NewServer(http.NotFoundHandler())
	url := server.URL
	server.Close()
	client := backend.New(url, "secret", time.Second)
	err := client.Send(context.Background(), sample)
	if err == nil || errors.Is(err, backend.ErrRejected) {
		t.Fatalf("got %v, want a transient error", err)
	}
}

func TestSendTimeoutIsTransient(
	t *testing.T,
) {
	slow := httptest.NewServer(http.HandlerFunc(func(
		_ http.ResponseWriter,
		_ *http.Request,
	) {
		time.Sleep(200 * time.Millisecond)
	}))
	t.Cleanup(slow.Close)
	client := backend.New(slow.URL, "secret", 20*time.Millisecond)
	err := client.Send(context.Background(), sample)
	if err == nil || errors.Is(err, backend.ErrRejected) {
		t.Fatalf("got %v, want a transient error", err)
	}
}
