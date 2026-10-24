package rabbit

import (
	"context"

	amqp "github.com/rabbitmq/amqp091-go"
)

// channel is the part of *amqp.Channel the publisher and the consumer use.
type channel interface {
	Confirm(noWait bool) error
	NotifyReturn(returns chan amqp.Return) chan amqp.Return
	IsClosed() bool
	PublishConfirmed(ctx context.Context, exchange, key string, mandatory, immediate bool, msg amqp.Publishing) (confirmation, error)
	QueueDeclare(name string, durable, autoDelete, exclusive, noWait bool, args amqp.Table) (amqp.Queue, error)
	QueueBind(name, key, exchange string, noWait bool, args amqp.Table) error
	Qos(prefetchCount, prefetchSize int, global bool) error
	Consume(queue, consumer string, autoAck, exclusive, noLocal, noWait bool, args amqp.Table) (<-chan amqp.Delivery, error)
}
