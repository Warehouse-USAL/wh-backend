package rabbit

import (
	"errors"
	"strings"
	"testing"
)

func TestWithHintFlagsTheMQTTPort(
	t *testing.T,
) {
	err := withHint("amqp://user:secret@rabbitmq:1883/", errors.New("dial tcp: refused"))
	if !strings.Contains(err.Error(), "5672") {
		t.Fatalf("error %q lacks the AMQP port hint", err)
	}
	if strings.Contains(err.Error(), "secret") {
		t.Fatalf("error %q leaks the credential", err)
	}
}

func TestWithHintFlagsAHandshakeRejection(
	t *testing.T,
) {
	cause := errors.New(`Exception (501) Reason: "EOF"`)
	err := withHint("amqp://rabbitmq:5672/", cause)
	if !errors.Is(err, cause) || !strings.Contains(err.Error(), "5672") {
		t.Fatalf("error %q does not wrap the cause with the hint", err)
	}
}

func TestWithHintLeavesOtherErrorsAlone(
	t *testing.T,
) {
	cause := errors.New("dial tcp: i/o timeout")
	if err := withHint("amqp://rabbitmq:5672/", cause); err != cause {
		t.Fatalf("error %v, want the original", err)
	}
	if err := withHint("amqp://rabbitmq:5672/", nil); err != nil {
		t.Fatalf("error %v, want nil", err)
	}
}
