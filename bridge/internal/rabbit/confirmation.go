package rabbit

import "context"

// confirmation is the part of *amqp.DeferredConfirmation the publisher uses.
type confirmation interface {
	WaitContext(ctx context.Context) (bool, error)
}
