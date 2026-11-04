package fakeinfra

import (
	"context"
	"encoding/json"
	"log/slog"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/orders"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

var publishPolicy = retry.Policy{Min: 200 * time.Millisecond, Max: 2 * time.Second}

type phase int

const (
	moving phase = iota
	returning
	aborting
)

// run is the order on the road.
type run struct {
	order       orderMessage
	index       int
	phase       phase
	forward     time.Duration
	legStarted  time.Time
	timer       *time.Timer
	cancelledBy bool
}

// central is single threaded: every input goes through one select loop, as in
// the real one, so no state needs a lock.
type central struct {
	publisher orders.Publisher
	cfg       Config
	logger    *slog.Logger

	known   map[string]bool // every order id ever received, including cancelled ones
	queue   []orderMessage
	current *run
	lastQR  *string
	fired   chan *run
}

func newCentral(
	publisher orders.Publisher,
	cfg Config,
	logger *slog.Logger,
) *central {
	if cfg.ReturnFactor <= 0 {
		cfg.ReturnFactor = 1
	}
	return &central{
		publisher: publisher, cfg: cfg, logger: logger,
		known: map[string]bool{}, fired: make(chan *run, 16),
	}
}

func (c *central) run(
	ctx context.Context,
	orderSource vehicles.Source,
	controlSource vehicles.Source,
) error {
	deliveries := orderSource.Deliveries(ctx)
	var controls <-chan vehicles.Delivery
	if controlSource != nil {
		controls = controlSource.Deliveries(ctx)
	}
	var tick <-chan time.Time
	if c.cfg.TelemetryEvery > 0 {
		ticker := time.NewTicker(c.cfg.TelemetryEvery)
		defer ticker.Stop()
		tick = ticker.C
	}
	c.telemetry(ctx)
	for deliveries != nil {
		select {
		case <-ctx.Done():
			c.stopTimer()
			return nil
		case d, ok := <-deliveries:
			if !ok {
				c.stopTimer()
				return nil
			}
			c.onOrder(ctx, d)
		case d, ok := <-controls:
			if !ok {
				controls = nil
				continue
			}
			c.onControl(ctx, d)
		case r := <-c.fired:
			if r == c.current {
				c.advance(ctx)
			}
		case <-tick:
			c.telemetry(ctx)
		}
	}
	return nil
}

func (c *central) stopTimer() {
	if c.current != nil && c.current.timer != nil {
		c.current.timer.Stop()
	}
}

func (c *central) onControl(
	ctx context.Context,
	d vehicles.Delivery,
) {
	defer func() { _ = d.Ack() }()
	var msg Control
	if err := json.Unmarshal(d.Body(), &msg); err != nil {
		c.logger.Warn("ignoring control message", "reason", "malformed", "payload_bytes", len(d.Body()))
		return
	}
	setBool(&c.cfg.DropCancels, msg.DropCancels)
	setBool(&c.cfg.DuplicateStatuses, msg.DuplicateStatuses)
	setBool(&c.cfg.StuckBusy, msg.StuckBusy)
	if msg.StepDelay != nil {
		if delay, err := time.ParseDuration(*msg.StepDelay); err == nil {
			c.cfg.StepDelay = delay
		}
	}
	c.logger.Info("control applied", "restart", msg.Restart, "drop_cancels", c.cfg.DropCancels,
		"duplicate_statuses", c.cfg.DuplicateStatuses, "stuck_busy", c.cfg.StuckBusy, "step_delay", c.cfg.StepDelay)
	if msg.Restart {
		c.stopTimer()
		c.current, c.queue, c.known, c.lastQR = nil, nil, map[string]bool{}, nil
		c.telemetry(ctx)
	}
}

func setBool(
	target *bool,
	value *bool,
) {
	if value != nil {
		*target = *value
	}
}

func (c *central) onOrder(
	ctx context.Context,
	d vehicles.Delivery,
) {
	defer func() { _ = d.Ack() }() // the real central acks once the order is saved
	var msg orderMessage
	if err := json.Unmarshal(d.Body(), &msg); err != nil {
		c.logger.Error("ignoring order", "reason", "malformed order", "payload_bytes", len(d.Body()))
		return
	}
	if msg.OrderID == "" {
		c.logger.Error("ignoring order", "reason", "no order_id", "payload_bytes", len(d.Body()))
		return
	}
	c.logger.Info("order received", "status", msg.Status, "payload_bytes", len(d.Body()))
	if msg.Status == "cancelled" {
		c.onCancel(ctx, msg.OrderID)
		return
	}
	if c.known[msg.OrderID] {
		return // already received (or cancelled before it arrived): ignored
	}
	c.known[msg.OrderID] = true
	if len(msg.Stops) == 0 {
		c.status(ctx, msg.OrderID, "cancelled", "")
		return
	}
	c.queue = append(c.queue, msg)
	c.dispatch(ctx)
}

func (c *central) onCancel(
	ctx context.Context,
	orderID string,
) {
	if c.cfg.DropCancels {
		c.logger.Warn("cancel dropped", "mode", "drop_cancels")
		return
	}
	known := c.known[orderID]
	c.known[orderID] = true
	switch {
	case c.current != nil && c.current.order.OrderID == orderID:
		c.abort(ctx)
	case known:
		c.dequeue(orderID) // queued, finished or already cancelled: nothing else to do
	}
}

func (c *central) dequeue(
	orderID string,
) {
	kept := c.queue[:0]
	for _, queued := range c.queue {
		if queued.OrderID != orderID {
			kept = append(kept, queued)
		}
	}
	c.queue = kept
}

// abort turns the order on the road around: the vehicle drives back for as
// long as it drove forward and nothing is reported about the order.
func (c *central) abort(
	ctx context.Context,
) {
	r := c.current
	r.cancelledBy = true
	if r.phase == returning || r.phase == aborting {
		return // already on its way back: only the way it ends changes
	}
	r.forward += time.Since(r.legStarted)
	r.phase = aborting
	c.schedule(r, time.Duration(float64(r.forward)*c.cfg.ReturnFactor))
	c.telemetry(ctx)
}

func (c *central) dispatch(
	ctx context.Context,
) {
	if c.current != nil || len(c.queue) == 0 {
		return
	}
	next := c.queue[0]
	c.queue = c.queue[1:]
	r := &run{order: next, phase: moving, legStarted: time.Now()}
	c.current = r
	c.status(ctx, next.OrderID, "in_progress", c.cfg.VehicleID)
	c.telemetry(ctx)
	c.schedule(r, c.cfg.StepDelay)
}

func (c *central) schedule(
	r *run,
	delay time.Duration,
) {
	if r.timer != nil {
		r.timer.Stop()
	}
	r.timer = time.AfterFunc(delay, func() { c.fired <- r })
}

// advance runs when a timer of the current order fires.
func (c *central) advance(
	ctx context.Context,
) {
	r := c.current
	switch r.phase {
	case moving:
		stop := r.order.Stops[r.index]
		c.lastQR = &stop
		r.index++
		r.forward += time.Since(r.legStarted)
		r.legStarted = time.Now()
		c.telemetry(ctx)
		if r.index < len(r.order.Stops) {
			c.schedule(r, c.cfg.StepDelay)
			return
		}
		r.phase = returning
		c.schedule(r, time.Duration(float64(r.forward)*c.cfg.ReturnFactor))
	case returning, aborting:
		c.finish(ctx)
	}
}

func (c *central) finish(
	ctx context.Context,
) {
	r := c.current
	c.current = nil
	// A cancelled order is never reported: the backend already closed it.
	if !r.cancelledBy {
		c.status(ctx, r.order.OrderID, "completed", c.cfg.VehicleID)
	}
	c.telemetry(ctx)
	c.dispatch(ctx)
}

func (c *central) status(
	ctx context.Context,
	orderID string,
	status string,
	vehicleID string,
) {
	body := encode(orderStatus{OrderID: orderID, Status: status, VehicleID: vehicleID, Timestamp: stamp(time.Now())})
	c.publish(ctx, body)
	if c.cfg.DuplicateStatuses {
		c.publish(ctx, body)
	}
}

func (c *central) telemetry(
	ctx context.Context,
) {
	status, battery := "idle", 90
	if c.current != nil || c.cfg.StuckBusy && c.lastQR != nil {
		status, battery = "busy", 80
	}
	c.publish(ctx, encode(telemetry{
		VehicleID: c.cfg.VehicleID, Status: status, Battery: battery, LastQR: c.lastQR, Timestamp: stamp(time.Now()),
	}))
}

func (c *central) publish(
	ctx context.Context,
	body []byte,
) {
	err := retry.Retry(ctx, publishPolicy, func(
		ctx context.Context,
	) error {
		return c.publisher.Publish(ctx, body)
	})
	if err == nil {
		c.logger.Info("published", "payload_bytes", len(body))
	}
}
