package rabbit

import (
	"context"

	amqp "github.com/rabbitmq/amqp091-go"
)

type amqpChannel struct {
	*amqp.Channel
}

func (c amqpChannel) PublishConfirmed(
	ctx context.Context,
	exchange string,
	key string,
	mandatory bool,
	immediate bool,
	msg amqp.Publishing,
) (confirmation, error) {
	return c.PublishWithDeferredConfirmWithContext(ctx, exchange, key, mandatory, immediate, msg)
}
