// Command fakeinfra stands in for Infra's central during end to end tests.
//
// It listens for orders on RabbitMQ and plays the central and one vehicle the
// way the real ones behave (docs/INFRA_CONTRACT.md): it queues orders, sends
// the vehicle through every stop, completes the order, and handles a cancel at
// once, driving the vehicle back without reporting the order. Failure modes
// are set by environment and can be flipped while it runs by publishing a JSON
// object to the routing key fakeinfra.control on amq.topic.
package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/fakeinfra"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/rabbit"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
)

const (
	exchange         = "amq.topic"
	ordersRoutingKey = "orders"
	vehiclesKey      = "vehicles"
	ordersQueue      = "fakeinfra.orders"
	controlKey       = "fakeinfra.control"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(logger); err != nil {
		logger.Error("fakeinfra stopped", "error", err)
		os.Exit(1)
	}
}

func run(
	logger *slog.Logger,
) error {
	url := os.Getenv("AMQP_URL")
	if url == "" {
		return fmt.Errorf("AMQP_URL is required")
	}
	cfg, err := configFromEnv()
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	policy := retry.Policy{Min: time.Second, Max: 10 * time.Second}
	orders := rabbit.NewConsumer(url, rabbit.Binding{Exchange: exchange, Queue: ordersQueue, RoutingKey: ordersRoutingKey}, policy, logger)
	control := rabbit.NewConsumer(url, rabbit.Binding{Exchange: exchange, Queue: controlKey, RoutingKey: controlKey}, policy, logger)
	publisher := rabbit.NewPublisher(url, exchange, vehiclesKey)
	logger.Info("fake infra running", "vehicle_id", cfg.VehicleID, "step_delay", cfg.StepDelay,
		"drop_cancels", cfg.DropCancels, "duplicate_statuses", cfg.DuplicateStatuses, "stuck_busy", cfg.StuckBusy)
	return fakeinfra.Run(ctx, orders, control, publisher, cfg, logger)
}

func configFromEnv() (fakeinfra.Config, error) {
	cfg := fakeinfra.Config{VehicleID: os.Getenv("FAKE_VEHICLE_ID"), ReturnFactor: 1}
	if cfg.VehicleID == "" {
		cfg.VehicleID = "VHC-001"
	}
	var err error
	if cfg.StepDelay, err = duration("FAKE_STEP_DELAY", time.Second); err != nil {
		return cfg, err
	}
	if cfg.TelemetryEvery, err = duration("FAKE_TELEMETRY_INTERVAL", 0); err != nil {
		return cfg, err
	}
	if raw := os.Getenv("FAKE_RETURN_FACTOR"); raw != "" {
		if cfg.ReturnFactor, err = strconv.ParseFloat(raw, 64); err != nil {
			return cfg, fmt.Errorf("FAKE_RETURN_FACTOR: %w", err)
		}
	}
	cfg.DropCancels = os.Getenv("FAKE_DROP_CANCELS") == "true"
	cfg.DuplicateStatuses = os.Getenv("FAKE_DUPLICATE_STATUSES") == "true"
	cfg.StuckBusy = os.Getenv("FAKE_STUCK_BUSY") == "true"
	return cfg, nil
}

func duration(
	name string,
	fallback time.Duration,
) (time.Duration, error) {
	raw := os.Getenv(name)
	if raw == "" {
		return fallback, nil
	}
	delay, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("%s: %w", name, err)
	}
	return delay, nil
}
