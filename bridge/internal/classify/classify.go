package classify

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
)

// Kinds of messages Infra publishes on the vehicles routing key.
const (
	KindOrderStatus      = "order_status"
	KindVehicleTelemetry = "vehicle_telemetry"
)

// ErrUnclassifiable is returned when a payload is valid JSON but carries
// neither an order_id nor a vehicle_id.
var ErrUnclassifiable = errors.New("payload has neither order_id nor vehicle_id")

var orderStatusFields = []string{"status", "vehicle_id", "timestamp"}

// Classify turns one Infra message into the backend request that reports it.
//
// A message with a non-empty order_id is an OrderStatus and keeps only
// status, vehicle_id and timestamp. Otherwise a message with a non-empty
// vehicle_id is a VehicleTelemetry and is forwarded as received without
// last_qr. Anything else yields ErrUnclassifiable.
func Classify(
	payload []byte,
) (Request, error) {
	var fields map[string]json.RawMessage
	err := json.Unmarshal(payload, &fields)
	if err != nil {
		return Request{}, fmt.Errorf("decode payload: %w", err)
	}
	orderID := stringField(fields, "order_id")
	if orderID != "" {
		return orderStatus(orderID, fields)
	}
	vehicleID := stringField(fields, "vehicle_id")
	if vehicleID != "" {
		return vehicleTelemetry(vehicleID, fields)
	}
	return Request{}, ErrUnclassifiable
}

func orderStatus(
	orderID string,
	fields map[string]json.RawMessage,
) (Request, error) {
	kept := make(map[string]json.RawMessage, len(orderStatusFields))
	for _, name := range orderStatusFields {
		copyField(kept, fields, name)
	}
	req, err := build("/infra/orders/"+url.PathEscape(orderID)+"/status", KindOrderStatus, kept)
	req.OrderID = orderID
	return req, err
}

func vehicleTelemetry(
	vehicleID string,
	fields map[string]json.RawMessage,
) (Request, error) {
	delete(fields, "last_qr")
	return build("/infra/vehicles/"+url.PathEscape(vehicleID)+"/telemetry", KindVehicleTelemetry, fields)
}

func build(
	path string,
	kind string,
	fields map[string]json.RawMessage,
) (Request, error) {
	body, err := json.Marshal(fields)
	if err != nil {
		return Request{}, fmt.Errorf("encode body: %w", err)
	}
	return Request{Path: path, Body: body, Kind: kind}, nil
}

func copyField(
	dst map[string]json.RawMessage,
	src map[string]json.RawMessage,
	name string,
) {
	value, ok := src[name]
	if ok {
		dst[name] = value
	}
}

func stringField(
	fields map[string]json.RawMessage,
	name string,
) string {
	var value string
	raw, ok := fields[name]
	if !ok {
		return ""
	}
	if json.Unmarshal(raw, &value) != nil {
		return ""
	}
	return value
}
