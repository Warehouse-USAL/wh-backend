package backend

import (
	"errors"
	"fmt"
)

// ErrRejected matches any RejectedError through errors.Is.
var ErrRejected = errors.New("backend rejected the request")

// RejectedError is returned when the backend answers with a status that
// retrying cannot fix, such as 400, 404 or 409.
type RejectedError struct {
	Status int
	Body   string
}

func (e *RejectedError) Error() string {
	return fmt.Sprintf("backend rejected the request: status %d: %s", e.Status, e.Body)
}

func (e *RejectedError) Is(
	target error,
) bool {
	return target == ErrRejected
}
