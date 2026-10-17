package orders

import "context"

// Publisher hands an order to Infra.
type Publisher interface {
	// Publish returns nil only once the broker has confirmed the message.
	Publish(ctx context.Context, body []byte) error
}
