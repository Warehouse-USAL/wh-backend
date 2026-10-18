package vehicles

import (
	"context"
	"log/slog"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/backend"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/telemetry"
)

// Pipeline moves messages from Infra to the backend, one at a time.
type Pipeline interface {
	// Run processes messages until ctx ends or the source closes.
	//
	// A message is acked once the backend accepts it or rejects it for good.
	// Transient failures are retried with backoff and the message is
	// requeued if ctx ends meanwhile. Run returns nil on a clean stop.
	Run(ctx context.Context) error
}

// New builds a Pipeline that reads from source and reports to client,
// retrying transient failures according to policy.
//
// Every message is counted on rec under orders_in (order statuses) or
// vehicles_in (telemetry, and anything unclassifiable): ok once accepted,
// retry per transient failure, dropped when unclassifiable or rejected, and
// error when stopped with the message in flight. Each POST is timed.
//
// An order status is sent with the request id ids remembers for its order,
// if any; telemetry carries none.
func New(
	source Source,
	client backend.Client,
	policy retry.Policy,
	logger *slog.Logger,
	rec telemetry.Recorder,
	ids *requestid.Store,
) Pipeline {
	policy.OnRetry = func(
		err error,
		delay time.Duration,
	) {
		logger.Warn("backend unavailable, retrying", "error", err, "retry_in", delay)
	}
	return &runner{source: source, client: client, policy: policy, logger: logger, rec: rec, ids: ids}
}
