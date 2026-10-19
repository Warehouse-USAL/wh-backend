// Package requestid carries the backend's request id across the bridge, so
// one id follows an order from the backend to Infra and back.
package requestid

import (
	"container/list"
	"context"
	"log/slog"
	"regexp"
	"sync"
	"time"
)

const (
	capacity = 10_000
	ttl      = time.Hour
)

// Header is the Kafka record and AMQP message header that carries the
// request id between the backend, the bridge and Infra.
const Header = "x-request-id"

// safe matches the ids the backend accepts: bounded and free of separators,
// so a value read from a broker can never forge a header or a log line.
var safe = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// Valid reports whether id may be forwarded or logged as a request id.
func Valid(
	id string,
) bool {
	return safe.MatchString(id)
}

// Attr returns id as the request_id log attribute, or an empty Attr, which
// slog handlers drop, when id is "". Log lines of messages without a request
// id therefore carry no request_id key at all.
func Attr(
	id string,
) slog.Attr {
	if id == "" {
		return slog.Attr{}
	}
	return slog.String("request_id", id)
}

type contextKey struct{}

// With returns a copy of ctx that carries id.
func With(
	ctx context.Context,
	id string,
) context.Context {
	return context.WithValue(ctx, contextKey{}, id)
}

// From returns the id carried by ctx, or "" when there is none.
func From(
	ctx context.Context,
) string {
	id, _ := ctx.Value(contextKey{}).(string)
	return id
}

type entry struct {
	orderID   string
	requestID string
	stored    time.Time
}

// Store remembers the request id of recent orders: the 10 000 most recently
// used, each for one hour. It is safe for concurrent use. Ids live only in
// memory, so after a restart a callback simply carries none.
type Store struct {
	mu      sync.Mutex
	entries map[string]*list.Element
	recency *list.List
	now     func() time.Time
}

// New returns an empty Store.
func New() *Store {
	return &Store{entries: make(map[string]*list.Element), recency: list.New(), now: time.Now}
}

// Remember records requestID as the id of orderID, evicting the least
// recently used order when the store is full. Callers skip Remember when a
// message carries no id, so a redelivered order without one keeps the id
// remembered earlier.
func (s *Store) Remember(
	orderID string,
	requestID string,
) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if element, ok := s.entries[orderID]; ok {
		s.recency.Remove(element)
	}
	s.entries[orderID] = s.recency.PushFront(&entry{orderID: orderID, requestID: requestID, stored: s.now()})
	if s.recency.Len() > capacity {
		s.remove(s.recency.Back())
	}
}

// Lookup returns the id remembered for orderID, or "" when there is none or
// it is older than one hour.
func (s *Store) Lookup(
	orderID string,
) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	element, ok := s.entries[orderID]
	if !ok {
		return ""
	}
	e := element.Value.(*entry)
	if s.now().Sub(e.stored) >= ttl {
		s.remove(element)
		return ""
	}
	s.recency.MoveToFront(element)
	return e.requestID
}

func (s *Store) remove(
	element *list.Element,
) {
	s.recency.Remove(element)
	delete(s.entries, element.Value.(*entry).orderID)
}
