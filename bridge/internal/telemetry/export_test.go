package telemetry

import (
	"log/slog"

	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
)

// NewWithReader builds a Recorder over reader so tests can read the metrics.
func NewWithReader(
	reader sdkmetric.Reader,
) (Recorder, error) {
	r, _, err := newRecorder(reader)
	return r, err
}

// NewFanout builds the slog fan-out New installs, over handlers.
func NewFanout(
	handlers ...slog.Handler,
) slog.Handler {
	return fanout(handlers)
}
