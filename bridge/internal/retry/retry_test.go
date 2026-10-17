package retry_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
)

var fastPolicy = retry.Policy{Min: time.Millisecond, Max: 4 * time.Millisecond}

type flaky struct {
	failures int
	calls    int
}

func (f *flaky) attempt(
	_ context.Context,
) error {
	f.calls++
	if f.calls <= f.failures {
		return errors.New("transient")
	}
	return nil
}

func TestRetrySucceedsAfterTransientFailures(
	t *testing.T,
) {
	f := &flaky{failures: 3}
	err := retry.Retry(context.Background(), fastPolicy, f.attempt)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if f.calls != 4 {
		t.Fatalf("got %d calls, want 4", f.calls)
	}
}

func TestRetryStopsOnPermanentError(
	t *testing.T,
) {
	calls := 0
	cause := errors.New("bad request")
	err := retry.Retry(context.Background(), fastPolicy, func(
		_ context.Context,
	) error {
		calls++
		return retry.Permanent(cause)
	})
	if !errors.Is(err, cause) {
		t.Fatalf("got %v, want the original cause", err)
	}
	if calls != 1 {
		t.Fatalf("got %d calls, want 1", calls)
	}
}

func TestRetryReturnsWhenContextIsCancelled(
	t *testing.T,
) {
	ctx, cancel := context.WithCancel(context.Background())
	calls := 0
	err := retry.Retry(ctx, fastPolicy, func(
		_ context.Context,
	) error {
		calls++
		cancel()
		return errors.New("transient")
	})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v, want context.Canceled", err)
	}
	if calls != 1 {
		t.Fatalf("got %d calls, want 1", calls)
	}
}

func TestRetryBacksOffUpToTheCap(
	t *testing.T,
) {
	var delays []time.Duration
	policy := retry.Policy{
		Min: time.Millisecond,
		Max: 4 * time.Millisecond,
		OnRetry: func(
			_ error,
			delay time.Duration,
		) {
			delays = append(delays, delay)
		},
	}
	f := &flaky{failures: 5}
	if err := retry.Retry(context.Background(), policy, f.attempt); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	want := []time.Duration{1, 2, 4, 4, 4}
	for i, d := range want {
		if delays[i] != d*time.Millisecond {
			t.Fatalf("delay %d = %v, want %v", i, delays[i], d*time.Millisecond)
		}
	}
}
