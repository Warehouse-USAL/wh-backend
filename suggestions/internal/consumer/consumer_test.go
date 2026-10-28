package consumer

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/segmentio/kafka-go"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/ingest"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

// fakeReader serves queued fetch results, then blocks until the context ends.
type fakeReader struct {
	mu        sync.Mutex
	queue     []fetchResult
	committed []kafka.Message
	closed    bool
	commitErr error
}

type fetchResult struct {
	msg kafka.Message
	err error
}

func (f *fakeReader) FetchMessage(ctx context.Context) (kafka.Message, error) {
	f.mu.Lock()
	if len(f.queue) > 0 {
		r := f.queue[0]
		f.queue = f.queue[1:]
		f.mu.Unlock()
		return r.msg, r.err
	}
	f.mu.Unlock()
	<-ctx.Done()
	return kafka.Message{}, ctx.Err()
}

func (f *fakeReader) CommitMessages(_ context.Context, msgs ...kafka.Message) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.committed = append(f.committed, msgs...)
	return f.commitErr
}

func (f *fakeReader) Close() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.closed = true
	return errors.New("close")
}

func (f *fakeReader) commits() int { f.mu.Lock(); defer f.mu.Unlock(); return len(f.committed) }

func msg(offset int64, body string) fetchResult {
	return fetchResult{msg: kafka.Message{Offset: offset, Value: []byte(body)}}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met in time")
		}
		time.Sleep(time.Millisecond)
	}
}

func run(c *Consumer) (cancel func(), done chan error) {
	ctx, cancel := context.WithCancel(context.Background())
	done = make(chan error, 1)
	go func() { done <- c.Run(ctx) }()
	return cancel, done
}

func TestAppliesThenCommitsInOrder(t *testing.T) {
	r := &fakeReader{queue: []fetchResult{
		msg(1, `{"orderId":"o1","userId":"u","items":[{"sku":"A","quantity":1}]}`),
		msg(2, `garbage`),
		msg(3, `{"orderId":"o3","userId":"u","items":[]}`),
	}}
	var mu sync.Mutex
	var seen []string
	c := New(r, func(_ context.Context, ev ingest.OrderCompleted) error {
		mu.Lock()
		seen = append(seen, ev.OrderID)
		mu.Unlock()
		return nil
	}, quiet)
	cancel, done := run(c)
	waitFor(t, func() bool { return r.commits() == 3 })
	if !c.Alive() {
		t.Error("consumer should be alive while running")
	}
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Errorf("Run = %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(seen) != 2 || seen[0] != "o1" || seen[1] != "o3" {
		t.Errorf("applied %v (the garbage message must be skipped, not applied)", seen)
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.closed {
		t.Error("reader not closed")
	}
	if c.Alive() {
		t.Error("consumer must not be alive after Run returns")
	}
}

func TestRetriesApplyUntilItSucceedsBeforeCommitting(t *testing.T) {
	r := &fakeReader{queue: []fetchResult{msg(7, `{"orderId":"o","items":[]}`)}}
	attempts := 0
	c := New(r, func(context.Context, ingest.OrderCompleted) error {
		attempts++
		if attempts < 3 {
			return errors.New("redis down")
		}
		return nil
	}, quiet)
	c.backoff = time.Millisecond
	cancel, done := run(c)
	waitFor(t, func() bool { return r.commits() == 1 })
	cancel()
	<-done
	if attempts != 3 {
		t.Errorf("attempts = %d, want 3", attempts)
	}
}

func TestShutdownDuringRetryLeavesMessageUncommitted(t *testing.T) {
	r := &fakeReader{queue: []fetchResult{msg(1, `{"orderId":"o","items":[]}`)}}
	started := make(chan struct{}, 1)
	c := New(r, func(context.Context, ingest.OrderCompleted) error {
		select {
		case started <- struct{}{}:
		default:
		}
		return errors.New("redis down")
	}, quiet)
	c.backoff = time.Hour
	cancel, done := run(c)
	<-started
	time.Sleep(10 * time.Millisecond)
	cancel()
	<-done
	if r.commits() != 0 {
		t.Error("an unapplied message must not be committed")
	}
}

func TestFetchErrorBacksOffAndCommitErrorIsLogged(t *testing.T) {
	r := &fakeReader{
		queue:     []fetchResult{{err: errors.New("broker down")}, msg(1, `{"orderId":"o","items":[]}`)},
		commitErr: errors.New("commit failed"),
	}
	c := New(r, func(context.Context, ingest.OrderCompleted) error { return nil }, quiet)
	c.backoff = time.Millisecond
	cancel, done := run(c)
	waitFor(t, func() bool { return r.commits() == 1 })
	cancel()
	<-done
}

func TestAliveGoesStale(t *testing.T) {
	r := &fakeReader{}
	c := New(r, nil, quiet)
	if c.Alive() {
		t.Error("not running yet")
	}
	c.running.Store(true)
	now := time.Now()
	c.now = func() time.Time { return now }
	c.beat.Store(now.UnixNano())
	if !c.Alive() {
		t.Error("fresh beat should be alive")
	}
	c.now = func() time.Time { return now.Add(staleAfter + time.Second) }
	if c.Alive() {
		t.Error("stale beat should not be alive")
	}
}
