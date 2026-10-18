package orders

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/telemetry"
)

type runner struct {
	consumer  Consumer
	publisher Publisher
	policy    retry.Policy
	logger    *slog.Logger
	rec       telemetry.Recorder
}

func (r *runner) Run(
	ctx context.Context,
) error {
	var err error
	for err == nil {
		err = r.step(ctx)
	}
	if errors.Is(err, context.Canceled) {
		return nil
	}
	return err
}

func (r *runner) step(
	ctx context.Context,
) error {
	record, err := r.consumer.Poll(ctx)
	if err != nil {
		return err
	}
	if !hasOrderID(record.Value()) {
		r.logger.Error("skipping record without a valid order_id", "payload", string(record.Value()))
		r.rec.Message(flow, "dropped")
		return r.consumer.Commit(ctx, record)
	}
	err = retry.Retry(ctx, r.policy, func(
		ctx context.Context,
	) error {
		return r.publisher.Publish(ctx, record.Value())
	})
	if err != nil {
		r.rec.Message(flow, "error")
		return err
	}
	r.logger.Info("order published to infra", "payload", string(record.Value()))
	r.rec.Message(flow, "ok")
	return r.consumer.Commit(ctx, record)
}

func hasOrderID(
	payload []byte,
) bool {
	var order struct {
		OrderID string `json:"order_id"`
	}
	if json.Unmarshal(payload, &order) != nil {
		return false
	}
	return order.OrderID != ""
}
