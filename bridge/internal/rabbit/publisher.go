package rabbit

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
)

var (
	errNacked      = errors.New("broker nacked the message")
	errUnroutable  = errors.New("message is unroutable")
	returnsBacklog = 16
)

type publisher struct {
	url        string
	exchange   string
	routingKey string
	dial       func(url string) (connection, error)

	mu      sync.Mutex
	conn    connection
	ch      channel
	returns chan amqp.Return
}

func (p *publisher) Publish(
	ctx context.Context,
	body []byte,
) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if err := p.ensure(); err != nil {
		return err
	}
	err := p.send(ctx, body)
	if err != nil && !errors.Is(err, errUnroutable) {
		p.reset()
	}
	return err
}

func (p *publisher) ensure() error {
	if p.ch != nil && !p.ch.IsClosed() {
		return nil
	}
	p.reset()
	conn, err := p.dial(p.url)
	if err != nil {
		return fmt.Errorf("dial rabbitmq: %w", err)
	}
	ch, err := conn.Channel()
	if err != nil {
		_ = conn.Close()
		return fmt.Errorf("open channel: %w", err)
	}
	if err = ch.Confirm(false); err != nil {
		_ = conn.Close()
		return fmt.Errorf("enable publisher confirms: %w", err)
	}
	p.conn, p.ch = conn, ch
	p.returns = ch.NotifyReturn(make(chan amqp.Return, returnsBacklog))
	return nil
}

func (p *publisher) reset() {
	if p.conn != nil {
		_ = p.conn.Close()
	}
	p.conn, p.ch, p.returns = nil, nil, nil
}

func (p *publisher) send(
	ctx context.Context,
	body []byte,
) error {
	confirmation, err := p.ch.PublishConfirmed(
		ctx,
		p.exchange,
		p.routingKey,
		true,
		false,
		amqp.Publishing{
			ContentType:  "application/json",
			DeliveryMode: amqp.Persistent,
			Timestamp:    time.Now(),
			Headers:      headers(ctx),
			Body:         body,
		},
	)
	if err != nil {
		return fmt.Errorf("publish to %s/%s: %w", p.exchange, p.routingKey, err)
	}
	acked, err := confirmation.WaitContext(ctx)
	if err != nil {
		return fmt.Errorf("wait for broker confirm: %w", err)
	}
	if !acked {
		return errNacked
	}
	return p.checkReturned()
}

func (p *publisher) checkReturned() error {
	select {
	case returned := <-p.returns:
		return fmt.Errorf("%w: %d %s (is a queue bound to %s with key %q?)", errUnroutable, returned.ReplyCode, returned.ReplyText, p.exchange, p.routingKey)
	default:
		return nil
	}
}

// headers carries the request id on the context, if any, to Infra.
func headers(
	ctx context.Context,
) amqp.Table {
	id := requestid.From(ctx)
	if id == "" {
		return nil
	}
	return amqp.Table{requestid.Header: id}
}
