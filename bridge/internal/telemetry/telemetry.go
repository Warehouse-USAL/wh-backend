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
	// Publish records how long one RabbitMQ publish attempt took, broker
	// confirm included, with result ok or error.
	Publish(flow, result string, d time.Duration)
	// Processing records how long one message took from its receipt to its
	// outcome (commit, ack or requeue), retries included, with result ok,
	// dropped or error.
	Processing(flow, result string, d time.Duration)
}

// Noop returns a Recorder that does nothing.
func Noop() Recorder {
	return noop{}
}

type noop struct{}

func (noop) Message(string, string)                   {}
func (noop) Callback(string, time.Duration)           {}
func (noop) Lag(string, int64)                        {}
func (noop) Publish(string, string, time.Duration)    {}
func (noop) Processing(string, string, time.Duration) {}

// durationBuckets match the backend's http.server.duration, in milliseconds.
var durationBuckets = []float64{5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000}

type recorder struct {
	messages   metric.Int64Counter
	duration   metric.Float64Histogram
	lag        metric.Int64Gauge
	publish    metric.Float64Histogram
	processing metric.Float64Histogram
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
	lag, err := meter.Int64Gauge("bridge.consumer.lag")
	if err != nil {
		return nil, nil, err
	}
	r := &recorder{messages: messages, lag: lag}
	histograms := map[string]*metric.Float64Histogram{
		"bridge.callback.duration":   &r.duration,
		"bridge.publish.duration":    &r.publish,
		"bridge.processing.duration": &r.processing,
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
	r.duration.Record(context.Background(), ms(d),
		metric.WithAttributes(attribute.String("flow", flow)))
}

func (r *recorder) Lag(
	flow string,
	n int64,
) {
	r.lag.Record(context.Background(), n, metric.WithAttributes(attribute.String("flow", flow)))
}

func (r *recorder) Publish(
	flow string,
	result string,
	d time.Duration,
) {
	r.publish.Record(context.Background(), ms(d), metric.WithAttributes(
		attribute.String("flow", flow),
		attribute.String("result", result),
	))
}

func (r *recorder) Processing(
	flow string,
	result string,
	d time.Duration,
) {
	r.processing.Record(context.Background(), ms(d), metric.WithAttributes(
		attribute.String("flow", flow),
		attribute.String("result", result),
	))
}

func ms(
	d time.Duration,
) float64 {
	return float64(d) / float64(time.Millisecond)
}
