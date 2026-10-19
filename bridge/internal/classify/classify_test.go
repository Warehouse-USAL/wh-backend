package classify_test

import (
	"errors"
	"testing"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/classify"
)

type classifyCase struct {
	name     string
	payload  string
	wantPath string
	wantKind string
	wantBody string
	wantErr  bool
}

func (c classifyCase) run(
	t *testing.T,
) {
	req, err := classify.Classify([]byte(c.payload))
	if c.wantErr {
		if err == nil {
			t.Fatalf("expected an error, got request %+v", req)
		}
		return
	}
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if req.Path != c.wantPath || req.Kind != c.wantKind {
		t.Fatalf("got path=%q kind=%q, want path=%q kind=%q", req.Path, req.Kind, c.wantPath, c.wantKind)
	}
	if string(req.Body) != c.wantBody {
		t.Fatalf("got body %s, want %s", req.Body, c.wantBody)
	}
}

func TestClassify(
	t *testing.T,
) {
	cases := []classifyCase{
		{
			name:     "order status in progress",
			payload:  `{"order_id":"ORD-1002","status":"in_progress","vehicle_id":"VHC-001","timestamp":"2026-10-16T10:06:00Z"}`,
			wantPath: "/infra/orders/ORD-1002/status",
			wantKind: classify.KindOrderStatus,
			wantBody: `{"status":"in_progress","timestamp":"2026-10-16T10:06:00Z","vehicle_id":"VHC-001"}`,
		},
		{
			name:     "order status with null vehicle",
			payload:  `{"order_id":"ORD-9","status":"cancelled","vehicle_id":null,"timestamp":"2026-10-16T10:07:05Z"}`,
			wantPath: "/infra/orders/ORD-9/status",
			wantKind: classify.KindOrderStatus,
			wantBody: `{"status":"cancelled","timestamp":"2026-10-16T10:07:05Z","vehicle_id":null}`,
		},
		{
			name:     "order status ignores extra fields",
			payload:  `{"order_id":"ORD-1","status":"completed","vehicle_id":"VHC-001","timestamp":"t","central_timestamp":"x"}`,
			wantPath: "/infra/orders/ORD-1/status",
			wantKind: classify.KindOrderStatus,
			wantBody: `{"status":"completed","timestamp":"t","vehicle_id":"VHC-001"}`,
		},
		{
			name:     "order id is path escaped",
			payload:  `{"order_id":"A/B C","status":"completed","vehicle_id":"V","timestamp":"t"}`,
			wantPath: "/infra/orders/A%2FB%20C/status",
			wantKind: classify.KindOrderStatus,
			wantBody: `{"status":"completed","timestamp":"t","vehicle_id":"V"}`,
		},
		{
			name:     "telemetry drops last_qr",
			payload:  `{"vehicle_id":"VHC-001","status":"busy","battery":79,"last_qr":"WH2|PA1","error":null,"timestamp":"2026-10-16T10:06:30Z"}`,
			wantPath: "/infra/vehicles/VHC-001/telemetry",
			wantKind: classify.KindVehicleTelemetry,
			wantBody: `{"battery":79,"error":null,"status":"busy","timestamp":"2026-10-16T10:06:30Z","vehicle_id":"VHC-001"}`,
		},
		{
			name:     "telemetry keeps error object",
			payload:  `{"vehicle_id":"VHC-001","status":"error","battery":74,"last_qr":"WH2|PA1","error":{"code":"CONNECTION_LOST","message":"Sin conexión con rover"},"timestamp":"t"}`,
			wantPath: "/infra/vehicles/VHC-001/telemetry",
			wantKind: classify.KindVehicleTelemetry,
			wantBody: `{"battery":74,"error":{"code":"CONNECTION_LOST","message":"Sin conexión con rover"},"status":"error","timestamp":"t","vehicle_id":"VHC-001"}`,
		},
		{
			name:     "telemetry with null battery and null last_qr",
			payload:  `{"vehicle_id":"VHC-001","status":"idle","battery":null,"last_qr":null,"error":null,"timestamp":"t"}`,
			wantPath: "/infra/vehicles/VHC-001/telemetry",
			wantKind: classify.KindVehicleTelemetry,
			wantBody: `{"battery":null,"error":null,"status":"idle","timestamp":"t","vehicle_id":"VHC-001"}`,
		},
		{
			name:     "empty order id falls back to telemetry",
			payload:  `{"order_id":"","vehicle_id":"VHC-002","status":"idle","timestamp":"t"}`,
			wantPath: "/infra/vehicles/VHC-002/telemetry",
			wantKind: classify.KindVehicleTelemetry,
			wantBody: `{"order_id":"","status":"idle","timestamp":"t","vehicle_id":"VHC-002"}`,
		},
		{name: "neither id", payload: `{"status":"idle"}`, wantErr: true},
		{name: "not json", payload: `nope`, wantErr: true},
		{name: "json array", payload: `[1,2]`, wantErr: true},
		{name: "json null", payload: `null`, wantErr: true},
	}
	for _, c := range cases {
		t.Run(c.name, c.run)
	}
}

func TestClassifyUnclassifiableIsSentinel(
	t *testing.T,
) {
	_, err := classify.Classify([]byte(`{"foo":1}`))
	if !errors.Is(err, classify.ErrUnclassifiable) {
		t.Fatalf("got %v, want ErrUnclassifiable", err)
	}
}
