package config

import (
	"strings"
	"testing"
	"time"
)

const secret = "0123456789abcdef0123456789abcdef"

func env(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func TestLoadDefaults(t *testing.T) {
	cfg, err := Load(env(map[string]string{"JWT_SECRET": secret}))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.HTTPAddr != ":8080" || cfg.KafkaTopic != "order.completed" || cfg.KafkaGroupID != "wh-suggestions" ||
		cfg.RedisAddr != "suggestions-redis:6379" || cfg.DecayFactor != 0.97 || cfg.DecayInterval != 15*time.Minute ||
		len(cfg.KafkaBrokers) != 1 || cfg.KafkaBrokers[0] != "redpanda:9092" {
		t.Fatalf("unexpected defaults: %+v", cfg)
	}
}

func TestLoadOverrides(t *testing.T) {
	cfg, err := Load(env(map[string]string{
		"JWT_SECRET": secret, "KAFKA_BROKERS": "a:1, b:2,", "HOT_DECAY_FACTOR": "0.5",
		"HOT_DECAY_INTERVAL": "1m", "REDIS_ADDR": "r:1", "REDIS_PASSWORD": "pw",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.KafkaBrokers) != 2 || cfg.KafkaBrokers[1] != "b:2" || cfg.DecayFactor != 0.5 ||
		cfg.DecayInterval != time.Minute || cfg.RedisAddr != "r:1" || cfg.RedisPassword != "pw" {
		t.Fatalf("unexpected: %+v", cfg)
	}
}

func TestLoadRejects(t *testing.T) {
	cases := map[string]map[string]string{
		"JWT_SECRET":             {},
		"JWT_SECRET short":       {"JWT_SECRET": "short"},
		"KAFKA_BROKERS":          {"JWT_SECRET": secret, "KAFKA_BROKERS": " , "},
		"HOT_DECAY_FACTOR":       {"JWT_SECRET": secret, "HOT_DECAY_FACTOR": "1.5"},
		"HOT_DECAY_FACTOR x":     {"JWT_SECRET": secret, "HOT_DECAY_FACTOR": "abc"},
		"HOT_DECAY_INTERVAL":     {"JWT_SECRET": secret, "HOT_DECAY_INTERVAL": "soon"},
		"HOT_DECAY_INTERVAL neg": {"JWT_SECRET": secret, "HOT_DECAY_INTERVAL": "-1s"},
	}
	for name, m := range cases {
		_, err := Load(env(m))
		if err == nil || !strings.Contains(err.Error(), strings.Fields(name)[0]) {
			t.Errorf("%s: err = %v", name, err)
		}
	}
}
