package orders

// Record is one order message taken from the orders topic.
type Record interface {
	// Value returns the raw JSON payload published by the backend.
	Value() []byte
}
