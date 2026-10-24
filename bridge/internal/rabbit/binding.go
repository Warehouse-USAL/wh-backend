package rabbit

// Binding names where a queue is attached on the broker: the topic exchange
// and the exact routing key that feeds it.
type Binding struct {
	Exchange   string
	Queue      string
	RoutingKey string
}
