package orders

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"time"

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
	start := time.Now()
	result, err := r.process(ctx, record)
	r.rec.Processing(flow, result, time.Since(start))
	return err
}

func (r *runner) process(
	ctx context.Context,
	record Record,
) (string, error) {
	requestID := record.RequestID()
	orderID := orderIDOf(record.Value())
	if orderID == "" {
		r.logger.Error("skipping record without a valid order_id", "payload_bytes", len(record.Value()), requestid.Attr(requestID))
		r.rec.Message(flow, "dropped")
		return outcome("dropped", r.consumer.Commit(ctx, record))
	}
	logger := r.logger.With("order_id", orderID, requestid.Attr(requestID))
	logger.Info("order consumed from kafka")
	if requestID != "" {
		r.ids.Remember(orderID, requestID)
	}
	policy := r.policy
	policy.OnRetry = func(
		err error,
		delay time.Duration,
	) {
		logger.Warn("rabbitmq publish failed, retrying", "error", err, "retry_in", delay)
		r.rec.Message(flow, "retry")
	}
	err := retry.Retry(requestid.With(ctx, requestID), policy, func(
		ctx context.Context,
	) error {
		return r.publish(ctx, record.Value())
	})
	if err != nil {
		logger.Info("stopping with an order unpublished", "error", err)
		r.rec.Message(flow, "error")
		return "error", err
	}
	logger.Info("order published to infra")
	r.rec.Message(flow, "ok")
	return outcome("ok", r.consumer.Commit(ctx, record))
}

func (r *runner) publish(
	ctx context.Context,
	body []byte,
) error {
	start := time.Now()
	err := r.publisher.Publish(ctx, body)
	result, _ := outcome("ok", err)
	r.rec.Publish(flow, result, time.Since(start))
	return err
}

func outcome(
	result string,
	err error,
) (string, error) {
	if err != nil {
		return "error", err
	}
	return result, nil
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
