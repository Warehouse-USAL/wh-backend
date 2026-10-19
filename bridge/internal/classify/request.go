package classify

// Request is a ready-to-send HTTP call to the backend: a path (already
// escaped), the JSON body, the kind of Infra message it came from and, for
// an order status, the order id.
type Request struct {
	Path    string
	Body    []byte
	Kind    string
	OrderID string
}
