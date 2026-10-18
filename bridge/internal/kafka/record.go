package kafka

import (
	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
)

const requestIDHeader = "x-request-id"

type record struct {
	raw *kgo.Record
}

func (r *record) Value() []byte {
	return r.raw.Value
}

// RequestID returns the backend's x-request-id header, or "" when it is
// missing or not a safe id.
func (r *record) RequestID() string {
	for _, header := range r.raw.Headers {
		if header.Key == requestIDHeader {
			return safeID(string(header.Value))
		}
	}
	return ""
}

func safeID(
	id string,
) string {
	if requestid.Valid(id) {
		return id
	}
	return ""
}
