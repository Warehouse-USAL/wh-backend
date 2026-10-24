// Package supervise runs the long-lived tasks of the bridge together.
package supervise

import "context"

// Supervise runs every task concurrently and returns when all of them have
// stopped. The first task to return, with or without an error, cancels the
// context given to the rest; Supervise returns that first result.
func Supervise(
	ctx context.Context,
	tasks ...func(context.Context) error,
) error {
	if len(tasks) == 0 {
		return nil
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	results := make(chan error, len(tasks))
	for _, task := range tasks {
		go launch(ctx, task, results)
	}
	first := <-results
	cancel()
	for range len(tasks) - 1 {
		<-results
	}
	return first
}

func launch(
	ctx context.Context,
	task func(context.Context) error,
	results chan<- error,
) {
	results <- task(ctx)
}
