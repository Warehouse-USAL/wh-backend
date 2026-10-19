package rabbit

import amqp "github.com/rabbitmq/amqp091-go"

type amqpConnection struct {
	*amqp.Connection
}

func dial(
	url string,
) (connection, error) {
	conn, err := amqp.DialConfig(url, amqp.Config{Dial: amqp.DefaultDial(dialTimeout)})
	if err != nil {
		return nil, err
	}
	return amqpConnection{conn}, nil
}

func (c amqpConnection) Channel() (channel, error) {
	ch, err := c.Connection.Channel()
	if err != nil {
		return nil, err
	}
	return amqpChannel{ch}, nil
}
