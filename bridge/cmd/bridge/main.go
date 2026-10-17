// Command bridge translates between the backend and Infra's RabbitMQ.
//
// Orders flow from the backend's Kafka topic to RabbitMQ. Order statuses and
// vehicle telemetry flow from RabbitMQ to the backend's HTTP API.
package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/backend"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/config"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/health"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/kafka"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/orders"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/rabbit"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/supervise"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

const (
	exchange         = "amq.topic"
	ordersRoutingKey = "orders"
	vehiclesKey      = "vehicles"
)

func main() {
	os.Exit(run())
}

func run() int {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		return probe()
	}
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		logger.Error("invalid configuration", "error", err)
		return 2
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if err = serve(ctx, cfg, logger); err != nil {
		logger.Error("bridge stopped", "error", err)
		return 1
	}
	logger.Info("bridge stopped")
	return 0
}

func probe() int {
	addr := os.Getenv("HEALTH_ADDR")
	if addr == "" {
		addr = ":8081"
	}
	if err := health.Probe(addr); err != nil {
		slog.Error("unhealthy", "error", err)
		return 1
	}
	return 0
}

func serve(
	ctx context.Context,
	cfg config.Config,
	logger *slog.Logger,
) error {
	logger.Info("starting bridge", "config", cfg)
	consumer, err := kafka.NewConsumer(cfg.KafkaBrokers, cfg.KafkaOrdersTopic, cfg.KafkaGroupID, logger)
	if err != nil {
		return err
	}
	defer consumer.Close()
	policy := retry.Policy{Min: cfg.RetryMin, Max: cfg.RetryMax}
	toInfra := orders.New(consumer, rabbit.NewPublisher(cfg.AMQPURL, exchange, ordersRoutingKey), policy, logger)
	fromInfra := vehicles.New(
		rabbit.NewConsumer(cfg.AMQPURL, rabbit.Binding{Exchange: exchange, Queue: cfg.AMQPQueue, RoutingKey: vehiclesKey}, policy, logger),
		backend.New(cfg.BackendURL, cfg.InfraAPIKey, cfg.HTTPTimeout),
		policy,
		logger,
	)
	return supervise.Supervise(ctx, toInfra.Run, fromInfra.Run, func(
		ctx context.Context,
	) error {
		return health.Health(ctx, cfg.HealthAddr)
	})
}
