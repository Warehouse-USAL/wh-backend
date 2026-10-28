// Package ingest turns order.completed events into Redis updates.
package ingest

import (
	"encoding/json"
	"errors"
	"fmt"
)

// OrderCompleted is the payload wh-backend publishes on the order.completed topic
// (backend OrderCompletedMessage; field names are camelCase and pinned there).
type OrderCompleted struct {
	OrderID         string `json:"orderId"`
	UserID          string `json:"userId"`
	DestinationArea string `json:"destinationArea"`
	Items           []Item `json:"items"`
	// CompletedAt is the ISO-8601 instant, kept as text: nothing reads it, so a malformed value
	// must not make an otherwise valid order undecodable.
	CompletedAt string `json:"completedAt"`
}

// Item is one order line of an OrderCompleted event.
type Item struct {
	SKU       string `json:"sku"`
	ProductID string `json:"productId"`
	Quantity  int    `json:"quantity"`
	// FromRecommendation is true when the buyer took the line from a suggestion of this service.
	// Absent in events published before the field existed (decodes to false). Ingestion does not
	// use it yet: it is carried so a conversion statistic can be added without a contract change.
	FromRecommendation bool `json:"fromRecommendation"`
}

// ErrInvalidEvent marks a payload that can never be applied, however often it is retried.
var ErrInvalidEvent = errors.New("invalid order.completed event")

// Decode parses one Kafka message value.
func Decode(raw []byte) (OrderCompleted, error) {
	var ev OrderCompleted
	if err := json.Unmarshal(raw, &ev); err != nil {
		return OrderCompleted{}, fmt.Errorf("%w: %w", ErrInvalidEvent, err)
	}
	return ev, nil
}
