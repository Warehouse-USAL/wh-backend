package rabbit

import amqp "github.com/rabbitmq/amqp091-go"

type delivery struct {
	message amqp.Delivery
}

func (d *delivery) Body() []byte {
	return d.message.Body
}

func (d *delivery) Ack() error {
	return d.message.Ack(false)
}

func (d *delivery) Requeue() error {
	return d.message.Nack(false, true)
}
