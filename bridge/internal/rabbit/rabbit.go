// Package rabbit adapts RabbitMQ to the ports the pipelines depend on.
package rabbit

import (
	"log/slog"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/orders"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

// NewConsumer returns a Source that declares a durable queue, binds it to
// the exact routing key in binding and delivers its messages one at a time
// with manual acks and a prefetch of one.
//
// It reconnects on its own, waiting according to policy, so the returned
// channel only closes when the context passed to Deliveries ends.
func NewConsumer(
	url string,
	binding Binding,
	policy retry.Policy,
	logger *slog.Logger,
) vehicles.Source {
	return &consumer{url: url, binding: binding, policy: policy, logger: logger, dial: dial}
}

// NewPublisher returns a Publisher that sends persistent JSON messages to
// exchange with routingKey and waits for the broker's publisher confirm.
//
// Messages are published as mandatory: if no queue is bound to the routing
// key the broker returns them and Publish reports an error instead of
// dropping the order silently. The connection is opened lazily and rebuilt
// after any failure.
func NewPublisher(
	url string,
	exchange string,
	routingKey string,
) orders.Publisher {
	return &publisher{url: url, exchange: exchange, routingKey: routingKey, dial: dial}
}
