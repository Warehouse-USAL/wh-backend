package kafka

import "github.com/twmb/franz-go/pkg/kgo"

type record struct {
	raw *kgo.Record
}

func (r *record) Value() []byte {
	return r.raw.Value
}
