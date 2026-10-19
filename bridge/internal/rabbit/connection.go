package rabbit

// connection is the part of *amqp.Connection the publisher and the consumer
// use.
type connection interface {
	Channel() (channel, error)
	Close() error
}
