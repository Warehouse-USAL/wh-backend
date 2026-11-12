package telemetry

import (
	"context"
	"log/slog"
	"strings"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	semconv "go.opentelemetry.io/otel/semconv/v1.43.0"
)

const exportInterval = 10 * time.Second

// New returns a Recorder that exports to the OTLP/HTTP collector at endpoint (a base URL such as
// http://otel-collector:4318) and a shutdown func that flushes it; the caller bounds the flush with
// the context it passes.
//
// With an empty endpoint it returns Noop and installs nothing. Export failures are logged at debug
// level and never reach the caller.
func New(
	ctx context.Context,
	endpoint string,
	service string,
	logger *slog.Logger,
) (Recorder, func(context.Context) error, error) {
	if endpoint == "" {
		return Noop(), func(context.Context) error { return nil }, nil
	}
	base := strings.TrimRight(endpoint, "/")
	otel.SetErrorHandler(otel.ErrorHandlerFunc(func(err error) {
		logger.Debug("telemetry export failed", "error", err)
	}))
	exporter, err := otlpmetrichttp.New(ctx, otlpmetrichttp.WithEndpointURL(base+"/v1/metrics"))
	if err != nil {
		return nil, nil, err
	}
	rec, meters, err := newRecorder(
		sdkmetric.NewPeriodicReader(exporter, sdkmetric.WithInterval(exportInterval)),
		sdkmetric.WithResource(resource.NewSchemaless(semconv.ServiceName(service))),
	)
	if err != nil {
		return nil, nil, err
	}
	return rec, meters.Shutdown, nil
}
