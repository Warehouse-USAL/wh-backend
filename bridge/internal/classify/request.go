package classify

// Request is a ready-to-send HTTP call to the backend: a path (already
// escaped), the JSON body, and the kind of Infra message it came from.
type Request struct {
	Path string
	Body []byte
	Kind string
}
