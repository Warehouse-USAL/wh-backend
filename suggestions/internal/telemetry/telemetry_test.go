package telemetry_test

import (
	"context"
	"testing"
	"time"

	"go.opentelemetry.io/otel/attribute"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/telemetry"
)

func collect(t *testing.T, reader *sdkmetric.ManualReader) map[string]metricdata.Metrics {
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

func tag(set attribute.Set, key string) string {
	v, _ := set.Value(attribute.Key(key))
	return v.AsString()
}

func TestNoopRecorderNeverPanics(t *testing.T) {
	r := telemetry.Noop()
	r.Event(telemetry.OutcomeOK)
	r.Lag(3)
	r.Ingest(telemetry.OutcomeOK, time.Millisecond)
	r.Request("cart", "2xx", time.Millisecond)
	r.RedisError(telemetry.OpQuery)
	r.EmptyRecommendations("hot")
}

func TestEmptyEndpointReturnsNoop(t *testing.T) {
	r, shutdown, err := telemetry.New(context.Background(), "", "wh-suggestions", nil)
	if err != nil || r != telemetry.Noop() || shutdown == nil {
		t.Fatalf("r=%v err=%v", r, err)
	}
	if err = shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
}

func TestCountersCarryTheirLowCardinalityTags(t *testing.T) {
	reader := sdkmetric.NewManualReader()
	r, err := telemetry.NewWithReader(reader)
	if err != nil {
		t.Fatal(err)
	}
	r.Event(telemetry.OutcomeOK)
	r.Event(telemetry.OutcomeOK)
	r.Event(telemetry.OutcomeDropped)
	r.RedisError(telemetry.OpIngest)
	r.EmptyRecommendations("cart")
	metrics := collect(t, reader)
	want := map[string]struct {
		key, val string
		n        int64
	}{
		"suggestions.events":                {"outcome", "ok", 2},
		"suggestions.redis.errors":          {"operation", "ingest", 1},
		"suggestions.recommendations.empty": {"endpoint", "cart", 1},
	}
	for name, w := range want {
		sum, ok := metrics[name].Data.(metricdata.Sum[int64])
		if !ok {
			t.Fatalf("%s = %+v", name, metrics[name])
		}
		found := false
		for _, dp := range sum.DataPoints {
			if tag(dp.Attributes, w.key) == w.val && dp.Value == w.n {
				found = true
			}
		}
		if !found {
			t.Errorf("%s has no %s=%s point with %d: %+v", name, w.key, w.val, w.n, sum.DataPoints)
		}
	}
	if got := len(metrics["suggestions.events"].Data.(metricdata.Sum[int64]).DataPoints); got != 2 {
		t.Errorf("events has %d points, want one per outcome (2)", got)
	}
}

func TestLagIsAGaugeWithoutTags(t *testing.T) {
	reader := sdkmetric.NewManualReader()
	r, err := telemetry.NewWithReader(reader)
	if err != nil {
		t.Fatal(err)
	}
	r.Lag(7)
	g, ok := collect(t, reader)["suggestions.consumer.lag"].Data.(metricdata.Gauge[int64])
	if !ok || len(g.DataPoints) != 1 || g.DataPoints[0].Value != 7 || g.DataPoints[0].Attributes.Len() != 0 {
		t.Fatalf("lag = %+v", g)
	}
}

func TestDurationHistogramsAreInMilliseconds(t *testing.T) {
	reader := sdkmetric.NewManualReader()
	r, err := telemetry.NewWithReader(reader)
	if err != nil {
		t.Fatal(err)
	}
	r.Ingest(telemetry.OutcomeError, 40*time.Millisecond)
	r.Request("user", "5xx", 2*time.Second)
	metrics := collect(t, reader)
	want := map[string]struct {
		tags map[string]string
		sum  float64
	}{
		"suggestions.ingest.duration": {map[string]string{"result": "error"}, 40},
		"suggestions.http.duration":   {map[string]string{"endpoint": "user", "status_class": "5xx"}, 2000},
	}
	for name, w := range want {
		m := metrics[name]
		h, ok := m.Data.(metricdata.Histogram[float64])
		if m.Unit != "ms" || !ok || len(h.DataPoints) != 1 {
			t.Fatalf("%s = %+v", name, m)
		}
		dp := h.DataPoints[0]
		if dp.Sum != w.sum || len(dp.Bounds) != 11 || dp.Attributes.Len() != len(w.tags) {
			t.Fatalf("%s point = %+v", name, dp)
		}
		for k, v := range w.tags {
			if tag(dp.Attributes, k) != v {
				t.Errorf("%s tag %s = %q, want %q", name, k, tag(dp.Attributes, k), v)
			}
		}
	}
	req, ok := metrics["suggestions.http.requests"].Data.(metricdata.Sum[int64])
	if !ok || len(req.DataPoints) != 1 || req.DataPoints[0].Value != 1 || tag(req.DataPoints[0].Attributes, "endpoint") != "user" {
		t.Fatalf("requests = %+v", req)
	}
}

func TestNewExportsToTheCollectorURL(t *testing.T) {
	if _, shutdown, err := telemetry.New(context.Background(), "http://127.0.0.1:1/", "wh-suggestions", nil); err != nil {
		t.Fatal(err)
	} else if shutdown == nil {
		t.Fatal("no shutdown")
	}
}
