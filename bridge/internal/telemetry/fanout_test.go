package telemetry_test

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/telemetry"
)

var errChild = errors.New("child failed")

type failingHandler struct {
	err     error
	handled int
}

func (h *failingHandler) Enabled(context.Context, slog.Level) bool {
	return true
}

func (h *failingHandler) Handle(
	_ context.Context,
	_ slog.Record,
) error {
	h.handled++
	return h.err
}

func (h *failingHandler) WithAttrs([]slog.Attr) slog.Handler {
	return h
}

func (h *failingHandler) WithGroup(string) slog.Handler {
	return h
}

func record(
	level slog.Level,
) slog.Record {
	r := slog.NewRecord(time.Unix(1_700_000_000, 0).UTC(), level, "order published", 0)
	r.AddAttrs(slog.String("payload", `{"order_id":"ORD-1"}`), slog.Int("n", 3))
	return r
}

func TestFanoutWritesTheSameStdoutBytesForAnInfoRecord(
	t *testing.T,
) {
	var direct, fanned bytes.Buffer
	if err := slog.NewJSONHandler(&direct, nil).Handle(context.Background(), record(slog.LevelInfo)); err != nil {
		t.Fatal(err)
	}
	other := &failingHandler{}
	f := telemetry.NewFanout(slog.NewJSONHandler(&fanned, nil), other)
	if err := f.Handle(context.Background(), record(slog.LevelInfo)); err != nil {
		t.Fatal(err)
	}
	if fanned.String() != direct.String() {
		t.Fatalf("stdout through the fan-out\n%s\nwant\n%s", fanned.String(), direct.String())
	}
	if other.handled != 1 {
		t.Fatalf("second handler saw %d records, want 1", other.handled)
	}
}

func TestFanoutFiltersRecordsBelowInfo(
	t *testing.T,
) {
	var out bytes.Buffer
	f := telemetry.NewFanout(slog.NewJSONHandler(&out, &slog.HandlerOptions{Level: slog.LevelDebug}))
	if f.Enabled(context.Background(), slog.LevelDebug) {
		t.Fatal("Debug is enabled")
	}
	if !f.Enabled(context.Background(), slog.LevelInfo) {
		t.Fatal("Info is disabled")
	}
	slog.New(f).Debug("dropped")
	if out.Len() != 0 {
		t.Fatalf("debug record written: %s", out.String())
	}
}

func TestFanoutAttrsAndGroupsReachEveryChild(
	t *testing.T,
) {
	var first, second bytes.Buffer
	f := telemetry.NewFanout(slog.NewJSONHandler(&first, nil), slog.NewJSONHandler(&second, nil))
	slog.New(f).With("a", 1).WithGroup("g").Info("hello", "k", "v")
	for name, out := range map[string]string{"first": first.String(), "second": second.String()} {
		if !strings.Contains(out, `"a":1`) || !strings.Contains(out, `"g":{"k":"v"}`) {
			t.Fatalf("%s child wrote %s", name, out)
		}
	}
}

func TestFanoutJoinsTheErrorsOfFailingChildren(
	t *testing.T,
) {
	errOther := errors.New("other failed")
	healthy := &failingHandler{}
	f := telemetry.NewFanout(&failingHandler{err: errChild}, healthy, &failingHandler{err: errOther})
	err := f.Handle(context.Background(), record(slog.LevelInfo))
	if !errors.Is(err, errChild) || !errors.Is(err, errOther) {
		t.Fatalf("Handle returned %v, want both child errors", err)
	}
	if healthy.handled != 1 {
		t.Fatalf("healthy child saw %d records, want 1 despite the failures", healthy.handled)
	}
}
