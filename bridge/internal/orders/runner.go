package orders

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/telemetry"
)

type runner struct {
	consumer  Consumer
	publisher Publisher
	policy    retry.Policy
	logger    *slog.Logger
	rec       telemetry.Recorder
	ids       *requestid.Store
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
	requestID := record.RequestID()
	orderID := orderIDOf(record.Value())
	if orderID == "" {
		r.logger.Error("skipping record without a valid order_id", "payload", string(record.Value()), requestid.Attr(requestID))
		r.rec.Message(flow, "dropped")
		return r.consumer.Commit(ctx, record)
	}
	if requestID != "" {
		r.ids.Remember(orderID, requestID)
	}
	err = retry.Retry(requestid.With(ctx, requestID), r.policy, func(
		ctx context.Context,
	) error {
		return r.publisher.Publish(ctx, record.Value())
	})
	if err != nil {
		r.rec.Message(flow, "error")
		return err
	}
	r.logger.Info("order published to infra", "payload", string(record.Value()), requestid.Attr(requestID))
	r.rec.Message(flow, "ok")
	return r.consumer.Commit(ctx, record)
}

func orderIDOf(
	payload []byte,
) string {
	var order struct {
		OrderID string `json:"order_id"`
	}
	if json.Unmarshal(payload, &order) != nil {
		return ""
	}
	return order.OrderID
}
