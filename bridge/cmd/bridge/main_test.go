package main

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

func noTask(
	context.Context,
) error {
	return nil
}

func TestLagReporterIsNotAddedWhenTelemetryIsNotLive(
	t *testing.T,
) {
	called := false
	tasks := withLagReporter([]func(context.Context) error{noTask}, false, func(
		context.Context,
	) error {
		called = true
		return nil
	}, slog.Default())
	if len(tasks) != 1 || called {
		t.Fatalf("tasks=%d called=%v, want the lag reporter left out", len(tasks), called)
	}
}

func TestLagReporterOutlivesItsOwnFailureAndStopsWithTheBridge(
	t *testing.T,
) {
	var logs bytes.Buffer
	reported := make(chan struct{})
	tasks := withLagReporter(nil, true, func(
		context.Context,
	) error {
		close(reported)
		return errors.New("admin down")
	}, slog.New(slog.NewTextHandler(&logs, nil)))
	if len(tasks) != 1 {
		t.Fatalf("tasks=%d, want the lag reporter added", len(tasks))
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- tasks[0](ctx) }()
	<-reported
	select {
	case err := <-done:
		t.Fatalf("task ended by itself with %v, which would stop the bridge", err)
	case <-time.After(50 * time.Millisecond):
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("task returned %v after cancel", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("task did not stop with its context")
	}
	if !strings.Contains(logs.String(), "admin down") {
		t.Fatalf("failure not logged: %q", logs.String())
	}
}

func TestOnlyARealRecorderCountsAsLiveTelemetry(
	t *testing.T,
) {
	rec, _, _ := startTelemetry(context.Background(), "", slog.Default())
	if rec != telemetry.Noop() {
		t.Fatal("without an endpoint the recorder must be the no-op one")
	}
}

func TestTelemetryFailureFallsBackToTheNoopRecorderAndTheGivenLogger(
	t *testing.T,
) {
	t.Cleanup(func() { newTelemetry = telemetry.New })
	newTelemetry = func(
		context.Context,
		string,
		string,
	) (telemetry.Recorder, func(context.Context) error, error) {
		return nil, nil, errors.New("exporter refused")
	}
	var logs bytes.Buffer
	given := slog.New(slog.NewTextHandler(&logs, nil))
	rec, shutdown, logger := startTelemetry(context.Background(), "http://collector:4318", given)
	if rec != telemetry.Noop() || logger != given {
		t.Fatalf("rec=%v logger=%v, want the no-op recorder and the given logger", rec, logger)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("no-op shutdown returned %v", err)
	}
	if !strings.Contains(logs.String(), "telemetry unavailable, running without it") || !strings.Contains(logs.String(), "exporter refused") {
		t.Fatalf("failure not logged: %q", logs.String())
	}
}
