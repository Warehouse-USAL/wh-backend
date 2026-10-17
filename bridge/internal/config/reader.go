package config

import (
	"fmt"
	"strings"
	"time"
)

type reader struct {
	getenv  func(string) string
	missing []string
	invalid []string
}

func (r *reader) required(
	key string,
) string {
	value := strings.TrimSpace(r.getenv(key))
	if value == "" {
		r.missing = append(r.missing, key)
	}
	return value
}

func (r *reader) optional(
	key string,
	fallback string,
) string {
	value := strings.TrimSpace(r.getenv(key))
	if value == "" {
		return fallback
	}
	return value
}

func (r *reader) duration(
	key string,
	fallback time.Duration,
) time.Duration {
	raw := strings.TrimSpace(r.getenv(key))
	if raw == "" {
		return fallback
	}
	value, err := time.ParseDuration(raw)
	if err != nil || value <= 0 {
		r.invalid = append(r.invalid, fmt.Sprintf("%s=%q", key, raw))
		return fallback
	}
	return value
}

func (r *reader) list(
	key string,
) []string {
	var items []string
	for _, item := range strings.Split(r.required(key), ",") {
		items = appendTrimmed(items, item)
	}
	return items
}

func appendTrimmed(
	items []string,
	item string,
) []string {
	trimmed := strings.TrimSpace(item)
	if trimmed == "" {
		return items
	}
	return append(items, trimmed)
}

func (r *reader) err() error {
	if len(r.missing) > 0 {
		return fmt.Errorf("missing required environment variables: %s", strings.Join(r.missing, ", "))
	}
	if len(r.invalid) > 0 {
		return fmt.Errorf("invalid durations: %s", strings.Join(r.invalid, ", "))
	}
	return nil
}
