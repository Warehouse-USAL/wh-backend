package decay

import (
	"context"
	"io"
	"log/slog"
	"math"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func setup(t *testing.T) (*miniredis.Miniredis, *redis.Client) {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { _ = rdb.Close() })
	return mr, rdb
}

func TestOnceScalesAndDropsNoise(t *testing.T) {
	mr, rdb := setup(t)
	_, _ = mr.ZAdd("hot:global", 100, "big")
	_, _ = mr.ZAdd("hot:global", 0.0101, "noise")
	n, err := Once(context.Background(), rdb, 0.97)
	if err != nil || n != 2 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	if got, _ := mr.ZScore("hot:global", "big"); math.Abs(got-97) > 1e-9 {
		t.Errorf("big = %v, want 97", got)
	}
	if m, _ := mr.ZMembers("hot:global"); len(m) != 1 || m[0] != "big" {
		t.Errorf("members = %v, noise should have been dropped", m)
	}
}

func TestOnceMissingKey(t *testing.T) {
	_, rdb := setup(t)
	if n, err := Once(context.Background(), rdb, 0.5); err != nil || n != 0 {
		t.Fatalf("n=%d err=%v", n, err)
	}
}

func TestRunTicksUntilCancelled(t *testing.T) {
	mr, rdb := setup(t)
	_, _ = mr.ZAdd("hot:global", 100, "a")
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { Run(ctx, rdb, 0.5, 5*time.Millisecond, log); close(done) }()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if got, _ := mr.ZScore("hot:global", "a"); got < 100 {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	cancel()
	<-done
	if got, _ := mr.ZScore("hot:global", "a"); got >= 100 {
		t.Errorf("score never decayed: %v", got)
	}
}

func TestRunLogsFailureAndContinues(t *testing.T) {
	mr, rdb := setup(t)
	mr.Close()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Millisecond)
	defer cancel()
	Run(ctx, rdb, 0.5, 5*time.Millisecond, log) // returns on timeout despite the errors
}
