package retry

import "time"

// Policy configures the exponential backoff used by Retry.
//
// The wait starts at Min, doubles after every failed attempt and never
// exceeds Max. OnRetry, when set, is called before each wait with the error
// that caused it.
type Policy struct {
	Min     time.Duration
	Max     time.Duration
	OnRetry func(err error, delay time.Duration)
}
