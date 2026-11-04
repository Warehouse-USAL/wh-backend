// Package fakeinfra simulates the Infra side of the contract for end to end
// tests. It follows what Infra's central really does (docs/INFRA_CONTRACT.md):
// orders wait in a queue until the vehicle is free, a cancel is handled at
// once and, for an order on the road, makes the vehicle drive back before it
// is free again without Infra saying anything about the order.
package fakeinfra

import (
	"context"
	"log/slog"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/orders"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

// Config tunes the simulated central. The flags model the failure modes
// documented in docs/INFRA_CONTRACT.md; they can also be flipped at run time
// with a Control message.
type Config struct {
	VehicleID string
	// StepDelay is the time the vehicle takes to reach a stop (and, on the
	// real rover, to wait at it).
	StepDelay time.Duration
	// ReturnFactor scales the way back: the real central reverses for as long
	// as the vehicle drove forward.
	ReturnFactor float64
	// TelemetryEvery repeats the vehicle telemetry on a timer; zero reports
	// only when something changes. The real central does it every 30 s.
	TelemetryEvery time.Duration
	// DropCancels loses every cancel (a message that never arrives).
	DropCancels bool
	// DuplicateStatuses publishes every order status twice (a resend after a
	// restart).
	DuplicateStatuses bool
	// StuckBusy keeps reporting the vehicle busy after its run ended, as if
	// the central never noticed it is free.
	StuckBusy bool
}

// Run plays the central against every order message delivered by orderSource
// and every Control message delivered by controlSource (nil for none) until
// ctx ends. Telemetry and order statuses go through publisher. It returns nil
// on a clean stop.
func Run(
	ctx context.Context,
	orderSource vehicles.Source,
	controlSource vehicles.Source,
	publisher orders.Publisher,
	cfg Config,
	logger *slog.Logger,
) error {
	c := newCentral(publisher, cfg, logger)
	return c.run(ctx, orderSource, controlSource)
}
