package telemetry

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"strings"
	"time"

	"go.opentelemetry.io/contrib/bridges/otelslog"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploghttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	sdklog "go.opentelemetry.io/otel/sdk/log"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	semconv "go.opentelemetry.io/otel/semconv/v1.43.0"
)

const exportInterval = 10 * time.Second

// New returns a Recorder that exports to the OTLP/HTTP collector at endpoint
// (a base URL such as http://otel-collector:4318) and a shutdown func that
// flushes it; the caller bounds the flush with the context it passes.
//
// With an empty endpoint it returns Noop and installs nothing. Otherwise it
// also replaces the default slog logger with one that writes the same JSON to
// stdout and ships each record to the collector. Export failures are logged at
// debug level and never reach the caller.
func New(
	ctx context.Context,
	endpoint string,
	service string,
) (Recorder, func(context.Context) error, error) {
	if endpoint == "" {
		return Noop(), func(context.Context) error { return nil }, nil
	}
	base := strings.TrimRight(endpoint, "/")
	res := resource.NewSchemaless(semconv.ServiceName(service))
	stdout := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	otel.SetErrorHandler(otel.ErrorHandlerFunc(func(err error) {
		stdout.Debug("telemetry export failed", "error", err)
	}))

	metricExporter, err := otlpmetrichttp.New(ctx, otlpmetrichttp.WithEndpointURL(base+"/v1/metrics"))
	if err != nil {
		return nil, nil, err
	}
	rec, meters, err := newRecorder(
		sdkmetric.NewPeriodicReader(metricExporter, sdkmetric.WithInterval(exportInterval)),
		sdkmetric.WithResource(res),
	)
	if err != nil {
		return nil, nil, err
	}
	logExporter, err := otlploghttp.New(ctx, otlploghttp.WithEndpointURL(base+"/v1/logs"))
	if err != nil {
		return nil, nil, errors.Join(err, meters.Shutdown(ctx))
	}
	logs := sdklog.NewLoggerProvider(
		sdklog.WithResource(res),
		sdklog.WithProcessor(sdklog.NewBatchProcessor(logExporter)),
	)
	slog.SetDefault(slog.New(fanout{
		stdout.Handler(),
		otelslog.NewHandler(service, otelslog.WithLoggerProvider(logs)),
	}))
	return rec, func(ctx context.Context) error {
		return errors.Join(meters.Shutdown(ctx), logs.Shutdown(ctx))
	}, nil
}

// fanout sends every record at Info or above to all its handlers, matching the
// level the stdout handler has always used.
type fanout []slog.Handler

func (f fanout) Enabled(
	_ context.Context,
	level slog.Level,
) bool {
	return level >= slog.LevelInfo
}

func (f fanout) Handle(
	ctx context.Context,
	r slog.Record,
) error {
	var err error
	for _, h := range f {
		err = errors.Join(err, h.Handle(ctx, r.Clone()))
	}
	return err
}

func (f fanout) WithAttrs(
	attrs []slog.Attr,
) slog.Handler {
	out := make(fanout, len(f))
	for i, h := range f {
		out[i] = h.WithAttrs(attrs)
	}
	return out
}

func (f fanout) WithGroup(
	name string,
) slog.Handler {
	out := make(fanout, len(f))
	for i, h := range f {
		out[i] = h.WithGroup(name)
	}
	return out
}
