package backend

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/classify"
)

// Client delivers translated Infra messages to the backend over HTTP.
type Client interface {
	// Send posts req to the backend.
	//
	// It returns nil on a 2xx answer, an error matching ErrRejected when the
	// backend refuses the request for good (4xx except 408 and 429), and any
	// other error when the failure is transient and the call may be retried.
	Send(ctx context.Context, req classify.Request) error
}

// New returns a Client that posts to baseURL authenticating with apiKey in
// the X-Api-Key header. Every call is bounded by timeout.
func New(
	baseURL string,
	apiKey string,
	timeout time.Duration,
) Client {
	return &httpClient{
		baseURL: strings.TrimRight(baseURL, "/"),
		apiKey:  apiKey,
		http:    &http.Client{Timeout: timeout},
	}
}
