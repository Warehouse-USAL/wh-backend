package rabbit

import (
	"context"
	"testing"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
)

func TestHeadersCarryTheContextRequestID(
	t *testing.T,
) {
	got := headers(requestid.With(context.Background(), "req-1"))
	if len(got) != 1 || got["x-request-id"] != "req-1" {
		t.Fatalf("headers %v, want x-request-id=req-1", got)
	}
}

func TestHeadersAreNilWithoutARequestID(
	t *testing.T,
) {
	if got := headers(context.Background()); got != nil {
		t.Fatalf("headers %v, want nil", got)
	}
}
