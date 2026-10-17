package retry

import (
	"context"
	"errors"
	"fmt"
	"time"
)

type backoff struct {
	policy Policy
	delay  time.Duration
}

func (b *backoff) step(
	ctx context.Context,
	attempt func(context.Context) error,
) (bool, error) {
	err := attempt(ctx)
	if err == nil {
		return true, nil
	}
	var permanent *permanentError
	if errors.As(err, &permanent) {
		return true, permanent.err
	}
	if b.policy.OnRetry != nil {
		b.policy.OnRetry(err, b.delay)
	}
	if waitErr := wait(ctx, b.delay); waitErr != nil {
		return true, fmt.Errorf("%w (last error: %v)", waitErr, err)
	}
	b.delay = min(b.delay*2, b.policy.Max)
	return false, nil
}

func wait(
	ctx context.Context,
	delay time.Duration,
) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
