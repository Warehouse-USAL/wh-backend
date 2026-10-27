// Package config loads the service configuration from the environment.
package config

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// minSecretLen mirrors wh-backend, which refuses JWT secrets shorter than 32 characters.
const minSecretLen = 32

// Config holds every setting the service reads at startup.
type Config struct {
	HTTPAddr      string
	RedisAddr     string
	RedisPassword string
	KafkaBrokers  []string
	KafkaTopic    string
	KafkaGroupID  string
	JWTSecret     string
	// DecayFactor multiplies every hot:global score once per DecayInterval.
	DecayFactor   float64
	DecayInterval time.Duration
}

// Load reads the configuration through getenv (os.Getenv in production), applying defaults that
// match docker-compose.yml. It fails when the JWT secret is missing or short, or a value is
// malformed, so the service never starts half-configured.
func Load(getenv func(string) string) (Config, error) {
	get := func(key, fallback string) string {
		if v := strings.TrimSpace(getenv(key)); v != "" {
			return v
		}
		return fallback
	}
	cfg := Config{
		HTTPAddr:      get("HTTP_ADDR", ":8080"),
		RedisAddr:     get("REDIS_ADDR", "suggestions-redis:6379"),
		RedisPassword: getenv("REDIS_PASSWORD"),
		KafkaTopic:    get("KAFKA_TOPIC", "order.completed"),
		KafkaGroupID:  get("KAFKA_GROUP_ID", "wh-suggestions"),
		JWTSecret:     getenv("JWT_SECRET"),
	}
	for b := range strings.SplitSeq(get("KAFKA_BROKERS", "redpanda:9092"), ",") {
		if b = strings.TrimSpace(b); b != "" {
			cfg.KafkaBrokers = append(cfg.KafkaBrokers, b)
		}
	}
	if len(cfg.KafkaBrokers) == 0 {
		return Config{}, errors.New("KAFKA_BROKERS has no broker")
	}
	if len(cfg.JWTSecret) < minSecretLen {
		return Config{}, fmt.Errorf("JWT_SECRET must be set and at least %d characters", minSecretLen)
	}
	factor, err := strconv.ParseFloat(get("HOT_DECAY_FACTOR", "0.97"), 64)
	if err != nil || factor <= 0 || factor >= 1 {
		return Config{}, errors.New("HOT_DECAY_FACTOR must be a number in (0, 1)")
	}
	cfg.DecayFactor = factor
	interval, err := time.ParseDuration(get("HOT_DECAY_INTERVAL", "15m"))
	if err != nil || interval <= 0 {
		return Config{}, errors.New("HOT_DECAY_INTERVAL must be a positive duration such as 15m")
	}
	cfg.DecayInterval = interval
	return cfg, nil
}
