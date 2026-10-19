package backend

import "fmt"

// UnavailableError is returned when the backend answers with a status that
// retrying may fix, such as 408, 429 or any 5xx. Body holds the start of the
// response, which may echo the request, so callers log Status rather than the
// error text.
type UnavailableError struct {
	Status int
	Body   string
}

func (e *UnavailableError) Error() string {
	return fmt.Sprintf("backend answered status %d: %s", e.Status, e.Body)
}
