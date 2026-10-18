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
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/backend"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/config"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/health"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/kafka"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/orders"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/rabbit"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/supervise"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/telemetry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

const (
	exchange         = "amq.topic"
	ordersRoutingKey = "orders"
	vehiclesKey      = "vehicles"
	flushTimeout     = 5 * time.Second
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
	rec, shutdown, logger := startTelemetry(ctx, cfg.OTLPEndpoint, logger)
	defer flushTelemetry(shutdown, logger)
	if err = serve(ctx, cfg, logger, rec); err != nil {
		logger.Error("bridge stopped", "error", err)
		return 1
	}
	logger.Info("bridge stopped")
	return 0
}

// startTelemetry builds the recorder and returns the logger to use from then
// on. Telemetry never blocks startup: if it cannot be set up the error is
// logged and the bridge runs with the no-op recorder and the stdout logger.
func startTelemetry(
	ctx context.Context,
	endpoint string,
	logger *slog.Logger,
) (telemetry.Recorder, func(context.Context) error, *slog.Logger) {
	rec, shutdown, err := telemetry.New(ctx, endpoint, "wh-bridge")
	if err != nil {
		logger.Error("telemetry unavailable, running without it", "error", err)
		return telemetry.Noop(), func(context.Context) error { return nil }, logger
	}
	if endpoint == "" {
		return rec, shutdown, logger
	}
	return rec, shutdown, slog.Default()
}

func flushTelemetry(
	shutdown func(context.Context) error,
	logger *slog.Logger,
) {
	ctx, cancel := context.WithTimeout(context.Background(), flushTimeout)
	defer cancel()
	if err := shutdown(ctx); err != nil {
		logger.Debug("telemetry flush failed", "error", err)
	}
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
	rec telemetry.Recorder,
) error {
	logger.Info("starting bridge", "config", cfg)
	consumer, err := kafka.NewConsumer(cfg.KafkaBrokers, cfg.KafkaOrdersTopic, cfg.KafkaGroupID, logger)
	if err != nil {
		return err
	}
	defer consumer.Close()
	policy := retry.Policy{Min: cfg.RetryMin, Max: cfg.RetryMax}
	// Shared by both pipelines: orders remember their request id, callbacks look it up.
	ids := requestid.New()
	toInfra := orders.New(consumer, rabbit.NewPublisher(cfg.AMQPURL, exchange, ordersRoutingKey), policy, logger, rec, ids)
	fromInfra := vehicles.New(
		rabbit.NewConsumer(cfg.AMQPURL, rabbit.Binding{Exchange: exchange, Queue: cfg.AMQPQueue, RoutingKey: vehiclesKey}, policy, logger),
		backend.New(cfg.BackendURL, cfg.InfraAPIKey, cfg.HTTPTimeout),
		policy,
		logger,
		rec,
		ids,
	)
	tasks := []func(context.Context) error{toInfra.Run, fromInfra.Run, func(
		ctx context.Context,
	) error {
		return health.Health(ctx, cfg.HealthAddr)
	}}
	// A no-op recorder means telemetry is off or failed to start: nothing would read the lag.
	tasks = withLagReporter(tasks, rec != telemetry.Noop(), func(
		ctx context.Context,
	) error {
		return kafka.ReportLag(ctx, cfg.KafkaBrokers, cfg.KafkaGroupID, cfg.KafkaOrdersTopic, rec, logger)
	}, logger)
	return supervise.Supervise(ctx, tasks...)
}

// withLagReporter adds report to tasks when telemetry is live. The reporter is
// best effort: supervise stops the bridge as soon as any task returns, so
// whatever report returns, its task keeps waiting for ctx and never ends the
// bridge by itself.
func withLagReporter(
	tasks []func(context.Context) error,
	live bool,
	report func(context.Context) error,
	logger *slog.Logger,
) []func(context.Context) error {
	if !live {
		return tasks
	}
	return append(tasks, func(
		ctx context.Context,
	) error {
		if err := report(ctx); err != nil {
			logger.Error("consumer lag reporting stopped", "error", err)
		}
		<-ctx.Done()
		return nil
	})
}
