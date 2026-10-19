package fakeinfra

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/orders"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

var publishPolicy = retry.Policy{Min: 200 * time.Millisecond, Max: 2 * time.Second}

type player struct {
	publisher orders.Publisher
	vehicleID string
	delay     time.Duration
	logger    *slog.Logger
}

func (p *player) next(
	ctx context.Context,
	deliveries <-chan vehicles.Delivery,
) bool {
	select {
	case <-ctx.Done():
		return false
	case delivery, ok := <-deliveries:
		if !ok {
			return false
		}
		p.play(ctx, delivery)
		return true
	}
}

func (p *player) play(
	ctx context.Context,
	delivery vehicles.Delivery,
) {
	steps, err := Script(delivery.Body(), p.vehicleID, time.Now())
	if err != nil {
		p.logger.Error("ignoring order", "reason", ignoredReason(err), "payload_bytes", len(delivery.Body()))
		_ = delivery.Ack()
		return
	}
	p.logger.Info("order received", "payload_bytes", len(delivery.Body()))
	if err = p.perform(ctx, steps); err != nil {
		_ = delivery.Requeue()
		return
	}
	_ = delivery.Ack()
}

func (p *player) perform(
	ctx context.Context,
	steps []Step,
) error {
	var err error
	for i := 0; i < len(steps) && err == nil; i++ {
		err = p.emit(ctx, i, steps[i])
	}
	return err
}

func (p *player) emit(
	ctx context.Context,
	index int,
	step Step,
) error {
	if err := p.spaceOut(ctx, index); err != nil {
		return err
	}
	err := retry.Retry(ctx, publishPolicy, func(
		ctx context.Context,
	) error {
		return p.publisher.Publish(ctx, step.Body)
	})
	if err == nil {
		p.logger.Info("published", "payload_bytes", len(step.Body))
	}
	return err
}

func (p *player) spaceOut(
	ctx context.Context,
	index int,
) error {
	if index == 0 {
		return nil
	}
	return pause(ctx, p.delay)
}

func pause(
	ctx context.Context,
	delay time.Duration,
) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func ignoredReason(
	err error,
) string {
	if errors.Is(err, ErrNoOrderID) {
		return "no order_id"
	}
	return "malformed order"
}
