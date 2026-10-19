package fakeinfra

import (
	"encoding/json"
	"errors"
	"fmt"
	"time"
)

// ErrNoOrderID is returned by Script for an order message without an
// order_id, which the fake vehicle cannot report on.
var ErrNoOrderID = errors.New("order has no order_id")

type order struct {
	OrderID string   `json:"order_id"`
	Status  string   `json:"status"`
	Stops   []string `json:"stops"`
}

type orderStatus struct {
	OrderID   string `json:"order_id"`
	Status    string `json:"status"`
	VehicleID string `json:"vehicle_id"`
	Timestamp string `json:"timestamp"`
}

type telemetry struct {
	VehicleID string  `json:"vehicle_id"`
	Status    string  `json:"status"`
	Battery   int     `json:"battery"`
	LastQR    *string `json:"last_qr"`
	Error     *string `json:"error"`
	Timestamp string  `json:"timestamp"`
}

// Script returns the messages a contract-conformant Infra publishes for one
// order, in order.
//
// A pending order is taken by vehicleID (in_progress), the vehicle reports
// the first stop and then every stop it visits, the order completes and the
// vehicle goes back to idle. A cancelled order only frees the vehicle. Every
// message is stamped with now.
func Script(
	payload []byte,
	vehicleID string,
	now time.Time,
) ([]Step, error) {
	var received order
	if err := json.Unmarshal(payload, &received); err != nil {
		return nil, fmt.Errorf("decode order: %w", err)
	}
	if received.OrderID == "" {
		return nil, ErrNoOrderID
	}
	stamp := now.UTC().Format(time.RFC3339)
	if received.Status == "cancelled" {
		return encode(telemetry{VehicleID: vehicleID, Status: "idle", Battery: 90, Timestamp: stamp})
	}
	return walk(received, vehicleID, stamp)
}

func walk(
	received order,
	vehicleID string,
	stamp string,
) ([]Step, error) {
	messages := []any{
		orderStatus{OrderID: received.OrderID, Status: "in_progress", VehicleID: vehicleID, Timestamp: stamp},
		busy(vehicleID, firstStop(received.Stops), stamp),
	}
	for _, stop := range received.Stops {
		messages = append(messages, busy(vehicleID, &stop, stamp))
	}
	messages = append(
		messages,
		orderStatus{OrderID: received.OrderID, Status: "completed", VehicleID: vehicleID, Timestamp: stamp},
		telemetry{VehicleID: vehicleID, Status: "idle", Battery: 90, Timestamp: stamp},
	)
	return encode(messages...)
}

func busy(
	vehicleID string,
	lastQR *string,
	stamp string,
) telemetry {
	return telemetry{VehicleID: vehicleID, Status: "busy", Battery: 80, LastQR: lastQR, Timestamp: stamp}
}

func firstStop(
	stops []string,
) *string {
	if len(stops) == 0 {
		return nil
	}
	return &stops[0]
}

func encode(
	messages ...any,
) ([]Step, error) {
	steps := make([]Step, 0, len(messages))
	for _, message := range messages {
		body, err := json.Marshal(message)
		if err != nil {
			return nil, fmt.Errorf("encode message: %w", err)
		}
		steps = append(steps, Step{Body: body})
	}
	return steps, nil
}
