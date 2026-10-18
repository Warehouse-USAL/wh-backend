package telemetry

import sdkmetric "go.opentelemetry.io/otel/sdk/metric"

// NewWithReader builds a Recorder over reader so tests can read the metrics.
func NewWithReader(
	reader sdkmetric.Reader,
) (Recorder, error) {
	r, _, err := newRecorder(reader)
	return r, err
}
