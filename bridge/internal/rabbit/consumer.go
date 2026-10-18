package rabbit

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/retry"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/vehicles"
)

const dialTimeout = 10 * time.Second

var (
	errStopped        = errors.New("consumer stopped")
	errConnectionLost = errors.New("rabbitmq connection lost")
)

type consumer struct {
	url     string
	binding Binding
	policy  retry.Policy
	logger  *slog.Logger
	dial    func(url string) (connection, error)
}

func (c *consumer) Deliveries(
	ctx context.Context,
) <-chan vehicles.Delivery {
	out := make(chan vehicles.Delivery)
	go c.serve(ctx, out)
	return out
}

func (c *consumer) serve(
	ctx context.Context,
	out chan<- vehicles.Delivery,
) {
	defer close(out)
	policy := c.policy
	policy.OnRetry = c.logReconnect
	_ = retry.Retry(ctx, policy, func(
		ctx context.Context,
	) error {
		return c.session(ctx, out)
	})
}

func (c *consumer) logReconnect(
	err error,
	delay time.Duration,
) {
	c.logger.Warn("rabbitmq consumer disconnected, reconnecting", "error", err, "retry_in", delay)
}

func (c *consumer) session(
	ctx context.Context,
	out chan<- vehicles.Delivery,
) error {
	conn, err := c.dial(c.url)
	if err != nil {
		return fmt.Errorf("dial rabbitmq: %w", err)
	}
	defer conn.Close()
	messages, err := c.open(conn)
	if err != nil {
		return err
	}
	c.logger.Info("consuming from rabbitmq", "queue", c.binding.Queue, "routing_key", c.binding.RoutingKey)
	err = c.forward(ctx, messages, out)
	if errors.Is(err, errStopped) {
		return nil
	}
	return err
}

func (c *consumer) open(
	conn connection,
) (<-chan amqp.Delivery, error) {
	ch, err := conn.Channel()
	if err != nil {
		return nil, fmt.Errorf("open channel: %w", err)
	}
	_, err = ch.QueueDeclare(c.binding.Queue, true, false, false, false, nil)
	if err != nil {
		return nil, fmt.Errorf("declare queue %s: %w", c.binding.Queue, err)
	}
	err = ch.QueueBind(c.binding.Queue, c.binding.RoutingKey, c.binding.Exchange, false, nil)
	if err != nil {
		return nil, fmt.Errorf("bind queue %s to %s/%s: %w", c.binding.Queue, c.binding.Exchange, c.binding.RoutingKey, err)
	}
	if err = ch.Qos(1, 0, false); err != nil {
		return nil, fmt.Errorf("set prefetch: %w", err)
	}
	messages, err := ch.Consume(c.binding.Queue, "", false, false, false, false, nil)
	if err != nil {
		return nil, fmt.Errorf("consume %s: %w", c.binding.Queue, err)
	}
	return messages, nil
}

func (c *consumer) forward(
	ctx context.Context,
	messages <-chan amqp.Delivery,
	out chan<- vehicles.Delivery,
) error {
	var err error
	for err == nil {
		err = c.pass(ctx, messages, out)
	}
	return err
}

func (c *consumer) pass(
	ctx context.Context,
	messages <-chan amqp.Delivery,
	out chan<- vehicles.Delivery,
) error {
	select {
	case <-ctx.Done():
		return errStopped
	case message, ok := <-messages:
		if !ok {
			return errConnectionLost
		}
		return hand(ctx, &delivery{message: message}, out)
	}
}

func hand(
	ctx context.Context,
	d vehicles.Delivery,
	out chan<- vehicles.Delivery,
) error {
	select {
	case <-ctx.Done():
		return errStopped
	case out <- d:
		return nil
	}
}
