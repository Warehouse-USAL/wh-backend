package fakeinfra

import (
	"encoding/json"
	"time"
)

type orderMessage struct {
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

type fault struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type telemetry struct {
	VehicleID string  `json:"vehicle_id"`
	Status    string  `json:"status"`
	Battery   int     `json:"battery"`
	LastQR    *string `json:"last_qr"`
	Error     *fault  `json:"error"`
	Timestamp string  `json:"timestamp"`
}

// Control is a message on the control routing key: it flips failure modes of
// a running fake Infra. Absent fields keep their value.
type Control struct {
	DropCancels       *bool   `json:"drop_cancels"`
	DuplicateStatuses *bool   `json:"duplicate_statuses"`
	StuckBusy         *bool   `json:"stuck_busy"`
	StepDelay         *string `json:"step_delay"`
	// Restart forgets every order, queued or on the road, like a central that
	// restarted without its database.
	Restart bool `json:"restart"`
}

func stamp(
	now time.Time,
) string {
	return now.UTC().Format(time.RFC3339)
}

func encode(
	message any,
) []byte {
	body, err := json.Marshal(message)
	if err != nil {
		panic(err) // the messages are plain structs: this cannot happen
	}
	return body
}
