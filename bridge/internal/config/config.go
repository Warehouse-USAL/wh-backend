package config

import (
	"fmt"
	"log/slog"
	"net/url"
	"time"
)

// Config holds every setting the bridge reads from the environment.
type Config struct {
	KafkaBrokers     []string
	KafkaOrdersTopic string
	KafkaGroupID     string
	AMQPURL          string
	AMQPQueue        string
	BackendURL       string
	InfraAPIKey      string
	HTTPTimeout      time.Duration
	RetryMin         time.Duration
	RetryMax         time.Duration
	HealthAddr       string
	OTLPEndpoint     string
}

// Load builds a Config from getenv, usually os.Getenv.
//
// KAFKA_BROKERS, AMQP_URL, BACKEND_URL and INFRA_API_KEY are required and
// have no default; Load names every missing one in a single error. The other
// variables fall back to the defaults documented in the README.
func Load(
	getenv func(string) string,
) (Config, error) {
	r := &reader{getenv: getenv}
	cfg := Config{
		KafkaBrokers:     r.list("KAFKA_BROKERS"),
		KafkaOrdersTopic: r.optional("KAFKA_ORDERS_TOPIC", "orders"),
		KafkaGroupID:     r.optional("KAFKA_GROUP_ID", "wh-bridge"),
		AMQPURL:          r.required("AMQP_URL"),
		AMQPQueue:        r.optional("AMQP_QUEUE", "wh-backend.vehicles"),
		BackendURL:       r.required("BACKEND_URL"),
		InfraAPIKey:      r.required("INFRA_API_KEY"),
		HTTPTimeout:      r.duration("HTTP_TIMEOUT", 5*time.Second),
		RetryMin:         r.duration("RETRY_MIN", time.Second),
		RetryMax:         r.duration("RETRY_MAX", 30*time.Second),
		HealthAddr:       r.optional("HEALTH_ADDR", ":8081"),
		OTLPEndpoint:     r.optional("OTEL_EXPORTER_OTLP_ENDPOINT", ""),
	}
	if err := r.err(); err != nil {
		return Config{}, err
	}
	if cfg.RetryMin > cfg.RetryMax {
		return Config{}, fmt.Errorf("RETRY_MIN (%s) is greater than RETRY_MAX (%s)", cfg.RetryMin, cfg.RetryMax)
	}
	return cfg, nil
}

// LogValue implements slog.LogValuer so a Config can be logged without
// leaking the AMQP password or the API key.
func (c Config) LogValue() slog.Value {
	return slog.GroupValue(
		slog.Any("kafka_brokers", c.KafkaBrokers),
		slog.String("kafka_orders_topic", c.KafkaOrdersTopic),
		slog.String("kafka_group_id", c.KafkaGroupID),
		slog.String("amqp_url", redact(c.AMQPURL)),
		slog.String("amqp_queue", c.AMQPQueue),
		slog.String("backend_url", c.BackendURL),
		slog.String("infra_api_key", "<redacted>"),
		slog.Duration("http_timeout", c.HTTPTimeout),
		slog.Duration("retry_min", c.RetryMin),
		slog.Duration("retry_max", c.RetryMax),
		slog.String("health_addr", c.HealthAddr),
		slog.String("otlp_endpoint", c.OTLPEndpoint),
	)
}

func redact(
	raw string,
) string {
	parsed, err := url.Parse(raw)
	if err != nil {
		return "<unparseable>"
	}
	return parsed.Redacted()
}
