package config_test

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/config"
)

func env(
	values map[string]string,
) func(string) string {
	return func(
		key string,
	) string {
		return values[key]
	}
}

func required() map[string]string {
	return map[string]string{
		"KAFKA_BROKERS": "redpanda:9092, other:9092",
		"AMQP_URL":      "amqp://user:s3cret@rabbit:5672/",
		"BACKEND_URL":   "http://backend:8080",
		"INFRA_API_KEY": "key-123",
	}
}

func TestLoadAppliesDefaults(
	t *testing.T,
) {
	cfg, err := config.Load(env(required()))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(cfg.KafkaBrokers) != 2 || cfg.KafkaBrokers[1] != "other:9092" {
		t.Fatalf("brokers = %v", cfg.KafkaBrokers)
	}
	if cfg.KafkaOrdersTopic != "orders" || cfg.KafkaGroupID != "wh-bridge" || cfg.AMQPQueue != "wh-backend.vehicles" {
		t.Fatalf("defaults not applied: %+v", cfg)
	}
	if cfg.HTTPTimeout != 5*time.Second || cfg.RetryMin != time.Second || cfg.RetryMax != 30*time.Second {
		t.Fatalf("duration defaults not applied: %+v", cfg)
	}
	if cfg.HealthAddr != ":8081" {
		t.Fatalf("health addr = %q", cfg.HealthAddr)
	}
}

func TestLoadReadsOverrides(
	t *testing.T,
) {
	values := required()
	values["KAFKA_ORDERS_TOPIC"] = "orders-v2"
	values["HTTP_TIMEOUT"] = "2s"
	values["RETRY_MIN"] = "100ms"
	values["RETRY_MAX"] = "1m"
	values["HEALTH_ADDR"] = ":9000"
	cfg, err := config.Load(env(values))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.KafkaOrdersTopic != "orders-v2" || cfg.HTTPTimeout != 2*time.Second || cfg.RetryMin != 100*time.Millisecond || cfg.RetryMax != time.Minute || cfg.HealthAddr != ":9000" {
		t.Fatalf("overrides not applied: %+v", cfg)
	}
}

func TestLoadReportsEveryMissingVariable(
	t *testing.T,
) {
	_, err := config.Load(env(map[string]string{}))
	if err == nil {
		t.Fatal("expected an error")
	}
	for _, name := range []string{"KAFKA_BROKERS", "AMQP_URL", "BACKEND_URL", "INFRA_API_KEY"} {
		if !strings.Contains(err.Error(), name) {
			t.Fatalf("error %q does not mention %s", err, name)
		}
	}
}

func TestLoadRejectsInvalidDuration(
	t *testing.T,
) {
	values := required()
	values["HTTP_TIMEOUT"] = "soon"
	_, err := config.Load(env(values))
	if err == nil || !strings.Contains(err.Error(), "HTTP_TIMEOUT") {
		t.Fatalf("got %v, want an error naming HTTP_TIMEOUT", err)
	}
}

func TestLoadRejectsRetryMinAboveMax(
	t *testing.T,
) {
	values := required()
	values["RETRY_MIN"] = "1m"
	values["RETRY_MAX"] = "1s"
	_, err := config.Load(env(values))
	if err == nil {
		t.Fatal("expected an error")
	}
}

func TestLogValueHidesSecrets(
	t *testing.T,
) {
	cfg, err := config.Load(env(required()))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	var out bytes.Buffer
	slog.New(slog.NewTextHandler(&out, nil)).Info("starting", "config", cfg)
	logged := out.String()
	if strings.Contains(logged, "s3cret") || strings.Contains(logged, "key-123") {
		t.Fatalf("secret leaked in log: %s", logged)
	}
	if !strings.Contains(logged, "rabbit:5672") {
		t.Fatalf("expected the AMQP host in the log: %s", logged)
	}
}

func TestLoadReadsOTLPEndpoint(
	t *testing.T,
) {
	cfg, err := config.Load(env(required()))
	if err != nil || cfg.OTLPEndpoint != "" {
		t.Fatalf("default = %q, err = %v", cfg.OTLPEndpoint, err)
	}
	values := required()
	values["OTEL_EXPORTER_OTLP_ENDPOINT"] = " http://otel-collector:4318 "
	cfg, err = config.Load(env(values))
	if err != nil || cfg.OTLPEndpoint != "http://otel-collector:4318" {
		t.Fatalf("endpoint = %q, err = %v", cfg.OTLPEndpoint, err)
	}
}
