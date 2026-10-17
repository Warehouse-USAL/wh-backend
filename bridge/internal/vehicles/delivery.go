package vehicles

// Delivery is one message taken from the vehicles queue.
type Delivery interface {
	// Body returns the raw JSON payload published by Infra.
	Body() []byte
	// Ack removes the message from the queue for good.
	Ack() error
	// Requeue puts the message back so it is delivered again.
	Requeue() error
}
