package kafka

import (
	"testing"

	"github.com/twmb/franz-go/pkg/kgo"
)

func withHeader(
	value string,
) *record {
	return &record{raw: &kgo.Record{Headers: []kgo.RecordHeader{
		{Key: "other", Value: []byte("x")},
		{Key: "x-request-id", Value: []byte(value)},
	}}}
}

func TestRecordReadsTheRequestIDHeader(
	t *testing.T,
) {
	if got := withHeader("req-1").RequestID(); got != "req-1" {
		t.Fatalf("got %q, want req-1", got)
	}
}

func TestHostileRequestIDHeaderIsDropped(
	t *testing.T,
) {
	if got := withHeader("evil\r\nX-Api-Key: stolen").RequestID(); got != "" {
		t.Fatalf("got %q, want it dropped", got)
	}
}

func TestRecordWithoutRequestIDHeaderHasNone(
	t *testing.T,
) {
	if got := (&record{raw: &kgo.Record{}}).RequestID(); got != "" {
		t.Fatalf("got %q", got)
	}
}
