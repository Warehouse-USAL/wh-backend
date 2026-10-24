package supervise_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/supervise"
)

func waitForCancel(
	ctx context.Context,
) error {
	<-ctx.Done()
	return nil
}

func TestFirstFailureCancelsTheOthersAndIsReturned(
	t *testing.T,
) {
	boom := errors.New("boom")
	fail := func(
		_ context.Context,
	) error {
		return boom
	}
	done := make(chan error, 1)
	go func() { done <- supervise.Supervise(context.Background(), waitForCancel, fail) }()
	select {
	case err := <-done:
		if !errors.Is(err, boom) {
			t.Fatalf("got %v, want boom", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Supervise did not return")
	}
}

func TestParentCancellationStopsEverything(
	t *testing.T,
) {
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- supervise.Supervise(ctx, waitForCancel, waitForCancel) }()
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("got %v, want nil", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Supervise did not return")
	}
}
