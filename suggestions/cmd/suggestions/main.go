// Command suggestions serves order-history based product recommendations.
//
// It consumes order.completed from Kafka into Redis and answers JWT-protected HTTP queries from
// that model. "suggestions healthcheck" probes /healthz for the container HEALTHCHECK.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/segmentio/kafka-go"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/api"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/auth"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/config"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/consumer"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/decay"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/ingest"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/recommend"
)

func main() { os.Exit(run()) }

func run() int {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	cfg, err := config.Load(os.Getenv)
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		return probe(os.Getenv("HTTP_ADDR"))
	}
	if err != nil {
		logger.Error("invalid configuration", "error", err)
		return 2
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if err = serve(ctx, cfg, logger); err != nil {
		logger.Error("suggestions stopped", "error", err)
		return 1
	}
	logger.Info("suggestions stopped")
	return 0
}

func serve(ctx context.Context, cfg config.Config, logger *slog.Logger) error {
	rdb := redis.NewClient(&redis.Options{Addr: cfg.RedisAddr, Password: cfg.RedisPassword})
	defer rdb.Close()

	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers: cfg.KafkaBrokers,
		Topic:   cfg.KafkaTopic,
		GroupID: cfg.KafkaGroupID,
		// A new group replays the whole topic, which is how the model is rebuilt after a reset.
		StartOffset: kafka.FirstOffset,
		MinBytes:    1,
		MaxBytes:    10 << 20,
		MaxWait:     time.Second,
	})
	cons := consumer.New(reader, func(ctx context.Context, ev ingest.OrderCompleted) error {
		return ingest.Apply(ctx, rdb, ev, cfg.DedupeTTL)
	}, logger)

	srv := &http.Server{
		Addr: cfg.HTTPAddr,
		Handler: api.NewRouter(api.Deps{
			Recs:          recommend.New(rdb, recommend.Options{}),
			Verifier:      auth.NewVerifier(cfg.JWTSecret),
			Ping:          func(ctx context.Context) error { return rdb.Ping(ctx).Err() },
			ConsumerAlive: cons.Alive,
			Log:           logger,
		}),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	var wg sync.WaitGroup
	wg.Go(func() { _ = cons.Run(ctx) })
	wg.Go(func() { decay.Run(ctx, rdb, cfg.DecayFactor, cfg.DecayInterval, logger) })

	errc := make(chan error, 1)
	go func() { errc <- srv.ListenAndServe() }()
	logger.Info("suggestions listening", "addr", cfg.HTTPAddr, "topic", cfg.KafkaTopic, "group", cfg.KafkaGroupID)

	var err error
	select {
	case <-ctx.Done():
	case err = <-errc:
	}
	shutCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if serr := srv.Shutdown(shutCtx); err == nil && serr != nil && !errors.Is(serr, http.ErrServerClosed) {
		err = serr
	}
	wg.Wait()
	if errors.Is(err, http.ErrServerClosed) {
		err = nil
	}
	return err
}

// probe is the container HEALTHCHECK: exit 0 iff GET /healthz answers 200.
func probe(addr string) int {
	if addr == "" {
		addr = ":8080"
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return 1
	}
	if host == "" || strings.HasPrefix(host, "0.0.0.0") {
		host = "127.0.0.1"
	}
	client := http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://%s/healthz", net.JoinHostPort(host, port)))
	if err != nil {
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
