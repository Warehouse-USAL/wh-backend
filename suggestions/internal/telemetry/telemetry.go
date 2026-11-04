// Package telemetry records the suggestions service's metrics and, when an OTLP endpoint is
// configured, exports them to the collector. Labels are fixed, small sets: never a user id or SKU.
package telemetry

import (
	"context"
	"time"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
)

// Outcomes of one order.completed event (Event).
const (
	OutcomeOK      = "ok"      // applied to Redis
	OutcomeError   = "error"   // one failed apply attempt; the consumer retries the same event
	OutcomeDropped = "dropped" // undecodable, skipped without being applied
)

// Redis operations that can fail (RedisError).
const (
	OpIngest = "ingest"
	OpQuery  = "query"
	OpHealth = "health"
)

// Recorder reports what the service is doing. Implementations never block and never fail the
// caller.
type Recorder interface {
	// Event counts one order.completed event with outcome ok, error or dropped.
	Event(outcome string)
	// Lag sets the number of events waiting to be consumed.
	Lag(n int64)
	// Ingest records how long applying one event to Redis took (the Lua script, or the
	// transaction for an event without an order id), with result ok or error.
	Ingest(result string, d time.Duration)
	// Request records one HTTP request of endpoint cart, user or hot, with its status class
	// (2xx, 4xx, 5xx) and latency.
	Request(endpoint, statusClass string, d time.Duration)
	// RedisError counts one Redis failure during operation ingest, query or health.
	RedisError(operation string)
	// EmptyRecommendations counts one 200 response of endpoint that carried no recommendation.
	EmptyRecommendations(endpoint string)
}

// Noop returns a Recorder that does nothing.
func Noop() Recorder {
	return noop{}
}

type noop struct{}

func (noop) Event(string)                          {}
func (noop) Lag(int64)                             {}
func (noop) Ingest(string, time.Duration)          {}
func (noop) Request(string, string, time.Duration) {}
func (noop) RedisError(string)                     {}
func (noop) EmptyRecommendations(string)           {}

// durationBuckets match the backend's http.server.duration, in milliseconds.
var durationBuckets = []float64{5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000}

type recorder struct {
	events   metric.Int64Counter
	lag      metric.Int64Gauge
	ingest   metric.Float64Histogram
	requests metric.Int64Counter
	duration metric.Float64Histogram
	redis    metric.Int64Counter
	empty    metric.Int64Counter
}

func newRecorder(
	reader sdkmetric.Reader,
	opts ...sdkmetric.Option,
) (*recorder, *sdkmetric.MeterProvider, error) {
	provider := sdkmetric.NewMeterProvider(append(opts, sdkmetric.WithReader(reader))...)
	meter := provider.Meter("wh-suggestions")
	r := &recorder{}
	var err error
	counters := map[string]*metric.Int64Counter{
		"suggestions.events":                &r.events,
		"suggestions.http.requests":         &r.requests,
		"suggestions.redis.errors":          &r.redis,
		"suggestions.recommendations.empty": &r.empty,
	}
	for name, target := range counters {
		if *target, err = meter.Int64Counter(name); err != nil {
			return nil, nil, err
		}
	}
	if r.lag, err = meter.Int64Gauge("suggestions.consumer.lag"); err != nil {
		return nil, nil, err
	}
	histograms := map[string]*metric.Float64Histogram{
		"suggestions.ingest.duration": &r.ingest,
		"suggestions.http.duration":   &r.duration,
	}
	for name, target := range histograms {
		if *target, err = milliseconds(meter, name); err != nil {
			return nil, nil, err
		}
	}
	return r, provider, nil
}

func milliseconds(
	meter metric.Meter,
	name string,
) (metric.Float64Histogram, error) {
	return meter.Float64Histogram(
		name,
		metric.WithUnit("ms"),
		metric.WithExplicitBucketBoundaries(durationBuckets...),
	)
}

func (r *recorder) Event(
	outcome string,
) {
	r.events.Add(context.Background(), 1, metric.WithAttributes(attribute.String("outcome", outcome)))
}

func (r *recorder) Lag(
	n int64,
) {
	r.lag.Record(context.Background(), n)
}

func (r *recorder) Ingest(
	result string,
	d time.Duration,
) {
	r.ingest.Record(context.Background(), ms(d),
		metric.WithAttributes(attribute.String("result", result)))
}

func (r *recorder) Request(
	endpoint string,
	statusClass string,
	d time.Duration,
) {
	attrs := metric.WithAttributes(
		attribute.String("endpoint", endpoint),
		attribute.String("status_class", statusClass),
	)
	r.requests.Add(context.Background(), 1, attrs)
	r.duration.Record(context.Background(), ms(d), attrs)
}

func (r *recorder) RedisError(
	operation string,
) {
	r.redis.Add(context.Background(), 1,
		metric.WithAttributes(attribute.String("operation", operation)))
}

func (r *recorder) EmptyRecommendations(
	endpoint string,
) {
	r.empty.Add(context.Background(), 1,
		metric.WithAttributes(attribute.String("endpoint", endpoint)))
}

func ms(
	d time.Duration,
) float64 {
	return float64(d) / float64(time.Millisecond)
}
