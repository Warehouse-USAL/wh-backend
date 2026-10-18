// Package telemetry records the bridge's metrics and, when an OTLP endpoint is
// configured, exports them and the logs to the collector.
package telemetry

import (
	"context"
	"time"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
)

// Recorder reports what the bridge is doing. Implementations never block and
// never fail the caller.
type Recorder interface {
	// Message counts one message for flow with result ok, retry, dropped or error.
	Message(flow, result string)
	// Callback records how long handling one message took.
	Callback(flow string, d time.Duration)
	// Lag sets the number of messages waiting to be consumed on flow.
	Lag(flow string, n int64)
}

// Noop returns a Recorder that does nothing.
func Noop() Recorder {
	return noop{}
}

type noop struct{}

func (noop) Message(string, string)         {}
func (noop) Callback(string, time.Duration) {}
func (noop) Lag(string, int64)              {}

// durationBuckets match the backend's http.server.duration, in milliseconds.
var durationBuckets = []float64{5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000}

type recorder struct {
	messages metric.Int64Counter
	duration metric.Float64Histogram
	lag      metric.Int64Gauge
}

func newRecorder(
	reader sdkmetric.Reader,
	opts ...sdkmetric.Option,
) (*recorder, *sdkmetric.MeterProvider, error) {
	provider := sdkmetric.NewMeterProvider(append(opts, sdkmetric.WithReader(reader))...)
	meter := provider.Meter("wh-bridge")
	messages, err := meter.Int64Counter("bridge.messages")
	if err != nil {
		return nil, nil, err
	}
	duration, err := meter.Float64Histogram(
		"bridge.callback.duration",
		metric.WithUnit("ms"),
		metric.WithExplicitBucketBoundaries(durationBuckets...),
	)
	if err != nil {
		return nil, nil, err
	}
	lag, err := meter.Int64Gauge("bridge.consumer.lag")
	if err != nil {
		return nil, nil, err
	}
	return &recorder{messages: messages, duration: duration, lag: lag}, provider, nil
}

func (r *recorder) Message(
	flow string,
	result string,
) {
	r.messages.Add(context.Background(), 1, metric.WithAttributes(
		attribute.String("flow", flow),
		attribute.String("result", result),
	))
}

func (r *recorder) Callback(
	flow string,
	d time.Duration,
) {
	r.duration.Record(context.Background(), float64(d)/float64(time.Millisecond),
		metric.WithAttributes(attribute.String("flow", flow)))
}

func (r *recorder) Lag(
	flow string,
	n int64,
) {
	r.lag.Record(context.Background(), n, metric.WithAttributes(attribute.String("flow", flow)))
}
