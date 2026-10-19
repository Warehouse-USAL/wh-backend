// Command fakeinfra stands in for Infra's central during end to end tests.
//
// It listens for orders on RabbitMQ and plays a vehicle that takes each one,
// visits its stops and completes it, publishing what the real central would.
package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
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
	delay, err := stepDelay()
	if err != nil {
		return err
	}
	vehicleID := os.Getenv("FAKE_VEHICLE_ID")
	if vehicleID == "" {
		vehicleID = "VHC-001"
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	policy := retry.Policy{Min: time.Second, Max: 10 * time.Second}
	source := rabbit.NewConsumer(url, rabbit.Binding{Exchange: exchange, Queue: ordersQueue, RoutingKey: ordersRoutingKey}, policy, logger)
	publisher := rabbit.NewPublisher(url, exchange, vehiclesKey)
	logger.Info("fake infra running", "vehicle_id", vehicleID, "step_delay", delay)
	return fakeinfra.Run(ctx, source, publisher, vehicleID, delay, logger)
}

func stepDelay() (time.Duration, error) {
	raw := os.Getenv("FAKE_STEP_DELAY")
	if raw == "" {
		return time.Second, nil
	}
	delay, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("FAKE_STEP_DELAY: %w", err)
	}
	return delay, nil
}
