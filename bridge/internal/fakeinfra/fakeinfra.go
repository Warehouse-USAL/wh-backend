// Package fakeinfra simulates the Infra side of the contract for end to end
// tests: it takes orders from the broker and plays a vehicle completing them.
package fakeinfra

import (
	"context"
	"log/slog"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/orders"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

// Run plays vehicleID against every order delivered by source until ctx
// ends or the source closes.
//
// For each order it publishes the messages of Script through publisher,
// waiting delay between consecutive ones, and acks the order once the last
// message went out. If ctx ends midway the order is requeued. Run returns
// nil on a clean stop.
func Run(
	ctx context.Context,
	source vehicles.Source,
	publisher orders.Publisher,
	vehicleID string,
	delay time.Duration,
	logger *slog.Logger,
) error {
	player := &player{publisher: publisher, vehicleID: vehicleID, delay: delay, logger: logger}
	deliveries := source.Deliveries(ctx)
	more := true
	for more {
		more = player.next(ctx, deliveries)
	}
	return nil
}
