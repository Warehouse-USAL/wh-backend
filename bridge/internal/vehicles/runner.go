package vehicles

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/backend"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/classify"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/telemetry"
)

const (
	flowOrders   = "orders_in"
	flowVehicles = "vehicles_in"
)

type runner struct {
	source Source
	client backend.Client
	policy retry.Policy
	logger *slog.Logger
	rec    telemetry.Recorder
	ids    *requestid.Store
}

func (r *runner) Run(
	ctx context.Context,
) error {
	deliveries := r.source.Deliveries(ctx)
	more := true
	for more {
		more = r.step(ctx, deliveries)
	}
	return nil
}

func (r *runner) step(
	ctx context.Context,
	deliveries <-chan Delivery,
) bool {
	select {
	case <-ctx.Done():
		return false
	case delivery, ok := <-deliveries:
		if !ok {
			return false
		}
		r.handle(ctx, delivery)
		return true
	}
}

func (r *runner) handle(
	ctx context.Context,
	delivery Delivery,
) {
	req, err := classify.Classify(delivery.Body())
	if err != nil {
		r.logger.Error("dropping unclassifiable message", "error", err, "payload", string(delivery.Body()))
		r.rec.Message(flowVehicles, "dropped")
		r.ack(delivery)
		return
	}
	requestID := r.requestIDOf(req)
	policy := r.policy
	policy.OnRetry = r.retrying(flowOf(req), requestID)
	err = retry.Retry(requestid.With(ctx, requestID), policy, func(
		ctx context.Context,
	) error {
		return r.send(ctx, req)
	})
	r.settle(delivery, req, requestID, err)
}

func (r *runner) requestIDOf(
	req classify.Request,
) string {
	if req.OrderID == "" {
		return ""
	}
	return r.ids.Lookup(req.OrderID)
}

func (r *runner) send(
	ctx context.Context,
	req classify.Request,
) error {
	start := time.Now()
	err := r.client.Send(ctx, req)
	r.rec.Callback(flowOf(req), time.Since(start))
	if errors.Is(err, backend.ErrRejected) {
		return retry.Permanent(err)
	}
	return err
}

func (r *runner) retrying(
	flow string,
	requestID string,
) func(error, time.Duration) {
	return func(
		err error,
		delay time.Duration,
	) {
		r.logger.Warn("backend unavailable, retrying", requestid.Attr(requestID), "error", err, "retry_in", delay)
		r.rec.Message(flow, "retry")
	}
}

func (r *runner) settle(
	delivery Delivery,
	req classify.Request,
	requestID string,
	err error,
) {
	if err == nil {
		r.logger.Info("delivered to backend", "kind", req.Kind, "path", req.Path, requestid.Attr(requestID))
		r.rec.Message(flowOf(req), "ok")
		r.ack(delivery)
		return
	}
	if errors.Is(err, backend.ErrRejected) {
		r.logger.Warn("backend rejected message, dropping", "kind", req.Kind, "path", req.Path, requestid.Attr(requestID), "error", err, "payload", string(req.Body))
		r.rec.Message(flowOf(req), "dropped")
		r.ack(delivery)
		return
	}
	r.logger.Info("stopping with a message in flight, requeueing", "kind", req.Kind, "path", req.Path, requestid.Attr(requestID))
	r.rec.Message(flowOf(req), "error")
	if requeueErr := delivery.Requeue(); requeueErr != nil {
		r.logger.Error("requeue failed", "error", requeueErr)
	}
}

func (r *runner) ack(
	delivery Delivery,
) {
	if err := delivery.Ack(); err != nil {
		r.logger.Error("ack failed", "error", err)
	}
}

func flowOf(
	req classify.Request,
) string {
	if req.Kind == classify.KindOrderStatus {
		return flowOrders
	}
	return flowVehicles
}
