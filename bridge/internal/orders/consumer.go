package orders

import "context"

// Consumer reads the orders topic with manual offset commits.
type Consumer interface {
	// Poll blocks until the next record is available or ctx ends.
	Poll(ctx context.Context) (Record, error)
	// Commit marks record, and everything before it, as processed.
	Commit(ctx context.Context, record Record) error
	// Close releases the connection to the broker.
	Close()
}
