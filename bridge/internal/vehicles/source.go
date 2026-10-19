package vehicles

import "context"

// Source yields the messages Infra publishes for the backend.
type Source interface {
	// Deliveries returns a channel with one message at a time. The channel
	// is closed when ctx ends. Implementations keep reconnecting on their
	// own, so a closed channel always means shutdown.
	Deliveries(ctx context.Context) <-chan Delivery
}
