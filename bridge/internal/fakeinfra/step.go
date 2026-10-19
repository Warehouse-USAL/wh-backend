package fakeinfra

// Step is one message the fake Infra publishes on the vehicles routing key.
type Step struct {
	Body []byte
}
