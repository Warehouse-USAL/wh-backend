package kafka

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/twmb/franz-go/pkg/kerr"
	"github.com/twmb/franz-go/pkg/kfake"
	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/kmsg"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/orders"
)

const (
	consumerTopic = "orders"
	consumerGroup = "wh-bridge"
	pollTimeout   = 10 * time.Second
)

func newCluster(
	t *testing.T,
) *kfake.Cluster {
	t.Helper()
	cluster, err := kfake.NewCluster(kfake.NumBrokers(1), kfake.SeedTopics(1, consumerTopic))
	if err != nil {
		t.Fatalf("start kfake: %v", err)
	}
	t.Cleanup(cluster.Close)
	return cluster
}

func produce(
	t *testing.T,
	cluster *kfake.Cluster,
	records ...*kgo.Record,
) {
	t.Helper()
	client, err := kgo.NewClient(kgo.SeedBrokers(cluster.ListenAddrs()...), kgo.DefaultProduceTopic(consumerTopic))
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if err = client.ProduceSync(context.Background(), records...).FirstErr(); err != nil {
		t.Fatalf("produce: %v", err)
	}
}

func values(
	payloads ...string,
) []*kgo.Record {
	records := make([]*kgo.Record, 0, len(payloads))
	for _, payload := range payloads {
		records = append(records, &kgo.Record{Value: []byte(payload)})
	}
	return records
}

func open(
	t *testing.T,
	cluster *kfake.Cluster,
	logger *slog.Logger,
) orders.Consumer {
	t.Helper()
	c, err := NewConsumer(cluster.ListenAddrs(), consumerTopic, consumerGroup, logger)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(c.Close)
	return c
}

func poll(
	t *testing.T,
	c orders.Consumer,
) orders.Record {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), pollTimeout)
	defer cancel()
	rec, err := c.Poll(ctx)
	if err != nil {
		t.Fatalf("poll: %v", err)
	}
	return rec
}

func TestConsumerConsumesFromTheCommittedOffset(
	t *testing.T,
) {
	cluster := newCluster(t)
	produce(t, cluster, values("v1", "v2", "v3")...)
	first := open(t, cluster, quiet)
	one, two := poll(t, first), poll(t, first)
	if string(one.Value()) != "v1" || string(two.Value()) != "v2" {
		t.Fatalf("a fresh group read %q, %q, want v1, v2 from the start", one.Value(), two.Value())
	}
	if err := first.Commit(context.Background(), one); err != nil {
		t.Fatalf("commit: %v", err)
	}
	first.Close()
	second := open(t, cluster, quiet)
	if got := poll(t, second); string(got.Value()) != "v2" {
		t.Fatalf("resumed at %q, want v2 right after the committed v1", got.Value())
	}
}

func TestConsumerForwardsRecordHeadersIncludingXRequestID(
	t *testing.T,
) {
	cluster := newCluster(t)
	produce(t, cluster, &kgo.Record{
		Value: []byte(`{"order_id":"ORD-1"}`),
		Headers: []kgo.RecordHeader{
			{Key: "content-type", Value: []byte("application/json")},
			{Key: "x-request-id", Value: []byte("req-1")},
		},
	})
	got := poll(t, open(t, cluster, quiet))
	if string(got.Value()) != `{"order_id":"ORD-1"}` || got.RequestID() != "req-1" {
		t.Fatalf("value %q request id %q", got.Value(), got.RequestID())
	}
}

func TestLagReportsEndMinusCommitted(
	t *testing.T,
) {
	cluster := newCluster(t)
	produce(t, cluster, values("v1", "v2", "v3")...)
	c := open(t, cluster, quiet)
	if err := c.Commit(context.Background(), poll(t, c)); err != nil {
		t.Fatalf("commit: %v", err)
	}
	rec := &lagRecorder{seen: make(chan struct{})}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- ReportLag(ctx, cluster.ListenAddrs(), consumerGroup, consumerTopic, rec, quiet) }()
	select {
	case <-rec.seen:
	case <-time.After(pollTimeout):
		t.Fatal("no lag reported")
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatalf("ReportLag returned %v", err)
	}
	if flows, last := rec.state(); flows[0] != "orders_out" || last != 2 {
		t.Fatalf("reported %v last=%d, want orders_out=2", flows, last)
	}
}

func TestCommitRejectsRecordsFromElsewhere(
	t *testing.T,
) {
	c := open(t, newCluster(t), quiet)
	if err := c.Commit(context.Background(), foreignRecord{}); err == nil {
		t.Fatal("expected an error for a record the consumer did not return")
	}
}

type foreignRecord struct{}

func (foreignRecord) Value() []byte { return nil }

func (foreignRecord) RequestID() string { return "" }

func TestPollStopsWhenTheContextEnds(
	t *testing.T,
) {
	c := open(t, newCluster(t), quiet)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := c.Poll(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("Poll returned %v, want context.Canceled", err)
	}
}

func TestPollFailsOnceTheClientIsClosed(
	t *testing.T,
) {
	c := open(t, newCluster(t), quiet)
	c.Close()
	if _, err := c.Poll(context.Background()); !errors.Is(err, errClosed) {
		t.Fatalf("Poll returned %v, want errClosed", err)
	}
}

type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(
	p []byte,
) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// failFetchOnce answers the first fetch with a partition error, so the
// client surfaces it in PollFetches, and lets every later fetch through.
func failFetchOnce(
	req kmsg.Request,
) (kmsg.Response, error, bool) {
	fetch := req.(*kmsg.FetchRequest)
	resp := fetch.ResponseKind().(*kmsg.FetchResponse)
	for _, topic := range fetch.Topics {
		failed := kmsg.NewFetchResponseTopic()
		failed.Topic, failed.TopicID = topic.Topic, topic.TopicID
		failed.Partitions = append(failed.Partitions, failedPartitions(topic)...)
		resp.Topics = append(resp.Topics, failed)
	}
	return resp, nil, true
}

func failedPartitions(
	topic kmsg.FetchRequestTopic,
) []kmsg.FetchResponseTopicPartition {
	out := make([]kmsg.FetchResponseTopicPartition, 0, len(topic.Partitions))
	for _, p := range topic.Partitions {
		failed := kmsg.NewFetchResponseTopicPartition()
		failed.Partition = p.Partition
		failed.ErrorCode = kerr.TopicAuthorizationFailed.Code
		out = append(out, failed)
	}
	return out
}

func TestFetchErrorsAreLoggedAndConsumingContinues(
	t *testing.T,
) {
	cluster := newCluster(t)
	produce(t, cluster, values("v1")...)
	cluster.ControlKey(int16(kmsg.Fetch), failFetchOnce)
	logs := &syncBuffer{}
	c := open(t, cluster, slog.New(slog.NewTextHandler(logs, nil)))
	if got := poll(t, c); string(got.Value()) != "v1" {
		t.Fatalf("read %q after the fetch error, want v1", got.Value())
	}
	if !strings.Contains(logs.String(), "kafka fetch error") || !strings.Contains(logs.String(), "partition=0") {
		t.Fatalf("logged %q, want the fetch error", logs.String())
	}
}

func TestNewConsumerRejectsInvalidBrokers(
	t *testing.T,
) {
	if _, err := NewConsumer([]string{"localhost:notaport"}, consumerTopic, consumerGroup, quiet); err == nil {
		t.Fatal("expected an error for an invalid seed broker")
	}
}
