package kafka

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/twmb/franz-go/pkg/kadm"
)

const lagTopic = "orders"

type fakeAdmin struct {
	starts  map[int32]int64
	ends    map[int32]int64
	commits map[int32]int64
	err     error
	fetch   error
}

func listed(
	offsets map[int32]int64,
) kadm.ListedOffsets {
	partitions := make(map[int32]kadm.ListedOffset, len(offsets))
	for p, at := range offsets {
		partitions[p] = kadm.ListedOffset{Topic: lagTopic, Partition: p, Offset: at}
	}
	return kadm.ListedOffsets{lagTopic: partitions}
}

func (a *fakeAdmin) ListStartOffsets(
	_ context.Context,
	_ ...string,
) (kadm.ListedOffsets, error) {
	return listed(a.starts), nil
}

func (a *fakeAdmin) ListEndOffsets(
	_ context.Context,
	_ ...string,
) (kadm.ListedOffsets, error) {
	return listed(a.ends), a.err
}

func (a *fakeAdmin) FetchOffsets(
	_ context.Context,
	_ string,
) (kadm.OffsetResponses, error) {
	partitions := make(map[int32]kadm.OffsetResponse, len(a.commits))
	for p, at := range a.commits {
		partitions[p] = kadm.OffsetResponse{Offset: kadm.Offset{Topic: lagTopic, Partition: p, At: at}}
	}
	return kadm.OffsetResponses{lagTopic: partitions}, a.fetch
}

type lagRecorder struct {
	mu   sync.Mutex
	lags []string
	last int64
	seen chan struct{}
	once sync.Once
}

func (r *lagRecorder) Message(string, string) {}

func (r *lagRecorder) Callback(string, time.Duration) {}

func (r *lagRecorder) Lag(
	flow string,
	n int64,
) {
	r.mu.Lock()
	r.lags = append(r.lags, flow)
	r.last = n
	r.mu.Unlock()
	r.once.Do(func() { close(r.seen) })
}

func (r *lagRecorder) state() ([]string, int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.lags...), r.last
}

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

type lagCase struct {
	name  string
	admin *fakeAdmin
	want  int64
}

func (c lagCase) run(
	t *testing.T,
) {
	got, err := consumerLag(context.Background(), c.admin, "wh-bridge", lagTopic)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != c.want {
		t.Fatalf("lag %d, want %d", got, c.want)
	}
}

func TestConsumerLagSumsEndMinusCommittedOverPartitions(
	t *testing.T,
) {
	cases := []lagCase{
		{
			name:  "committed everywhere",
			admin: &fakeAdmin{ends: map[int32]int64{0: 10, 1: 7}, commits: map[int32]int64{0: 4, 1: 7}},
			want:  6,
		},
		{
			name:  "fresh group counts from the start offset",
			admin: &fakeAdmin{starts: map[int32]int64{0: 5, 1: 0}, ends: map[int32]int64{0: 9, 1: 3}},
			want:  7,
		},
		{
			name:  "no commit reported as -1",
			admin: &fakeAdmin{starts: map[int32]int64{0: 2}, ends: map[int32]int64{0: 6}, commits: map[int32]int64{0: -1}},
			want:  4,
		},
		{
			name:  "commit below the retained start",
			admin: &fakeAdmin{starts: map[int32]int64{0: 8}, ends: map[int32]int64{0: 10}, commits: map[int32]int64{0: 3}},
			want:  2,
		},
		{
			name:  "empty topic",
			admin: &fakeAdmin{},
			want:  0,
		},
	}
	for _, c := range cases {
		t.Run(c.name, c.run)
	}
}

func TestConsumerLagFailsWhenOffsetsCannotBeRead(
	t *testing.T,
) {
	for _, admin := range []*fakeAdmin{
		{err: errors.New("broker down")},
		{fetch: errors.New("coordinator unavailable")},
	} {
		if _, err := consumerLag(context.Background(), admin, "wh-bridge", lagTopic); err == nil {
			t.Fatalf("expected an error from %+v", admin)
		}
	}
}

func TestReportLagRejectsInvalidBrokers(
	t *testing.T,
) {
	rec := &lagRecorder{seen: make(chan struct{})}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if err := ReportLag(ctx, []string{"localhost:notaport"}, "wh-bridge", lagTopic, rec, quiet); err == nil {
		t.Fatal("expected an error for an invalid seed broker")
	}
}

func TestLagReporterReportsUntilCancelled(
	t *testing.T,
) {
	rec := &lagRecorder{seen: make(chan struct{})}
	admin := &fakeAdmin{ends: map[int32]int64{0: 12}, commits: map[int32]int64{0: 2}}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- lagReporter(ctx, admin, "wh-bridge", lagTopic, rec, time.Millisecond, quiet) }()
	select {
	case <-rec.seen:
	case <-time.After(2 * time.Second):
		t.Fatal("no lag reported")
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatalf("lagReporter returned %v", err)
	}
	flows, last := rec.state()
	if flows[0] != "orders_out" || last != 10 {
		t.Fatalf("reported %v last=%d", flows, last)
	}
}

func TestLagReporterSkipsFailedRounds(
	t *testing.T,
) {
	rec := &lagRecorder{seen: make(chan struct{})}
	admin := &fakeAdmin{err: errors.New("broker down")}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if err := lagReporter(ctx, admin, "wh-bridge", lagTopic, rec, time.Millisecond, quiet); err != nil {
		t.Fatalf("lagReporter returned %v", err)
	}
	if flows, _ := rec.state(); len(flows) != 0 {
		t.Fatalf("reported %v while the broker was down", flows)
	}
}

func TestReportLagStopsWithContextWhenBrokersAreUnreachable(
	t *testing.T,
) {
	rec := &lagRecorder{seen: make(chan struct{})}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if err := ReportLag(ctx, []string{"127.0.0.1:1"}, "wh-bridge", lagTopic, rec, quiet); err != nil {
		t.Fatalf("ReportLag returned %v", err)
	}
	if flows, _ := rec.state(); len(flows) != 0 {
		t.Fatalf("reported %v without a broker", flows)
	}
}
