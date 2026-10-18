package telemetry_test

import (
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"go.opentelemetry.io/otel/attribute"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/telemetry"
)

func collect(
	t *testing.T,
	reader *sdkmetric.ManualReader,
) map[string]metricdata.Metrics {
	t.Helper()
	var rm metricdata.ResourceMetrics
	if err := reader.Collect(context.Background(), &rm); err != nil {
		t.Fatalf("collect: %v", err)
	}
	out := map[string]metricdata.Metrics{}
	for _, sm := range rm.ScopeMetrics {
		for _, m := range sm.Metrics {
			out[m.Name] = m
		}
	}
	return out
}

func TestNoopRecorderNeverPanics(
	t *testing.T,
) {
	r := telemetry.Noop()
	r.Message("orders_out", "ok")
	r.Callback("orders_out", time.Millisecond)
	r.Lag("orders_out", 3)
}

func TestEmptyEndpointReturnsNoop(
	t *testing.T,
) {
	r, shutdown, err := telemetry.New(context.Background(), "", "wh-bridge")
	if err != nil || r == nil || shutdown == nil {
		t.Fatalf("r=%v err=%v", r, err)
	}
	r.Message("orders_in", "ok")
	if err = shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
}

func TestMessageCounterCarriesFlowAndResultTags(
	t *testing.T,
) {
	reader := sdkmetric.NewManualReader()
	r, err := telemetry.NewWithReader(reader)
	if err != nil {
		t.Fatal(err)
	}
	r.Message("orders_out", "ok")
	r.Message("orders_out", "ok")
	r.Message("vehicles_in", "retry")
	sum, ok := collect(t, reader)["bridge.messages"].Data.(metricdata.Sum[int64])
	if !ok || !sum.IsMonotonic || len(sum.DataPoints) != 2 {
		t.Fatalf("sum = %+v ok=%v", sum, ok)
	}
	want := map[[2]string]int64{{"orders_out", "ok"}: 2, {"vehicles_in", "retry"}: 1}
	for _, dp := range sum.DataPoints {
		flow, _ := dp.Attributes.Value(attribute.Key("flow"))
		result, _ := dp.Attributes.Value(attribute.Key("result"))
		if want[[2]string{flow.AsString(), result.AsString()}] != dp.Value {
			t.Fatalf("unexpected point %v = %d", dp.Attributes, dp.Value)
		}
	}
}

func TestCallbackHistogramRecordsMilliseconds(
	t *testing.T,
) {
	reader := sdkmetric.NewManualReader()
	r, err := telemetry.NewWithReader(reader)
	if err != nil {
		t.Fatal(err)
	}
	r.Callback("orders_in", 30*time.Millisecond)
	m := collect(t, reader)["bridge.callback.duration"]
	if m.Unit != "ms" {
		t.Fatalf("unit = %q", m.Unit)
	}
	h, ok := m.Data.(metricdata.Histogram[float64])
	if !ok || len(h.DataPoints) != 1 {
		t.Fatalf("histogram = %+v", m.Data)
	}
	dp := h.DataPoints[0]
	if dp.Count != 1 || dp.Sum != 30 {
		t.Fatalf("count=%d sum=%v", dp.Count, dp.Sum)
	}
	wantBounds := []float64{5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000}
	if len(dp.Bounds) != len(wantBounds) {
		t.Fatalf("bounds = %v", dp.Bounds)
	}
	for i := range wantBounds {
		if dp.Bounds[i] != wantBounds[i] {
			t.Fatalf("bounds = %v", dp.Bounds)
		}
	}
}

func TestLagGaugeKeepsLatestValuePerFlow(
	t *testing.T,
) {
	reader := sdkmetric.NewManualReader()
	r, err := telemetry.NewWithReader(reader)
	if err != nil {
		t.Fatal(err)
	}
	r.Lag("orders_out", 9)
	r.Lag("orders_out", 4)
	g, ok := collect(t, reader)["bridge.consumer.lag"].Data.(metricdata.Gauge[int64])
	if !ok || len(g.DataPoints) != 1 || g.DataPoints[0].Value != 4 {
		t.Fatalf("gauge = %+v", g)
	}
}

func TestEndpointExportsMetricsAndLogsOnShutdown(
	t *testing.T,
) {
	previous := slog.Default()
	t.Cleanup(func() { slog.SetDefault(previous) })
	var mu sync.Mutex
	paths := map[string]bool{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		paths[r.URL.Path] = true
		mu.Unlock()
	}))
	defer server.Close()
	r, shutdown, err := telemetry.New(context.Background(), server.URL+"/", "wh-bridge")
	if err != nil {
		t.Fatal(err)
	}
	r.Message("orders_out", "ok")
	slog.Debug("dropped below info")
	slog.With("a", 1).WithGroup("g").Info("hello", "k", "v")
	if err = shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if !paths["/v1/metrics"] || !paths["/v1/logs"] {
		t.Fatalf("exported paths = %v", paths)
	}
}

func TestUnreachableCollectorNeverFailsShutdownCallers(
	t *testing.T,
) {
	previous := slog.Default()
	t.Cleanup(func() { slog.SetDefault(previous) })
	r, shutdown, err := telemetry.New(context.Background(), "http://127.0.0.1:1", "wh-bridge")
	if err != nil {
		t.Fatal(err)
	}
	r.Message("orders_out", "error")
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = shutdown(ctx)
}
