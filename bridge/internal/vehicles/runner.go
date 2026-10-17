package vehicles

import (
	"context"
	"errors"
	"log/slog"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/backend"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/classify"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
)

type runner struct {
	source Source
	client backend.Client
	policy retry.Policy
	logger *slog.Logger
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
		r.ack(delivery)
		return
	}
	err = retry.Retry(ctx, r.policy, func(
		ctx context.Context,
	) error {
		return r.send(ctx, req)
	})
	r.settle(delivery, req, err)
}

func (r *runner) send(
	ctx context.Context,
	req classify.Request,
) error {
	err := r.client.Send(ctx, req)
	if errors.Is(err, backend.ErrRejected) {
		return retry.Permanent(err)
	}
	return err
}

func (r *runner) settle(
	delivery Delivery,
	req classify.Request,
	err error,
) {
	if err == nil {
		r.logger.Info("delivered to backend", "kind", req.Kind, "path", req.Path)
		r.ack(delivery)
		return
	}
	if errors.Is(err, backend.ErrRejected) {
		r.logger.Warn("backend rejected message, dropping", "kind", req.Kind, "path", req.Path, "error", err, "payload", string(req.Body))
		r.ack(delivery)
		return
	}
	r.logger.Info("stopping with a message in flight, requeueing", "kind", req.Kind, "path", req.Path)
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
