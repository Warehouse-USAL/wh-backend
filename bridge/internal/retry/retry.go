package retry

import "context"

// Permanent marks err as not worth retrying. Retry returns the original
// error as soon as an attempt reports it.
func Permanent(
	err error,
) error {
	return &permanentError{err: err}
}

// Retry calls attempt until it succeeds, reports a Permanent error, or ctx
// ends. Between attempts it waits according to policy.
//
// On success it returns nil. On a Permanent error it returns the wrapped
// error. When ctx ends it returns the context error joined with the last
// attempt error.
func Retry(
	ctx context.Context,
	policy Policy,
	attempt func(context.Context) error,
) error {
	state := &backoff{policy: policy, delay: policy.Min}
	done := false
	var err error
	for !done {
		done, err = state.step(ctx, attempt)
	}
	return err
}
