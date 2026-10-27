package rabbit

import (
	"fmt"
	"net/url"
	"strings"
)

const mqttPort = "1883"

// withHint explains the most common AMQP_URL mistake: pointing it at RabbitMQ's MQTT plugin
// (port 1883) instead of the AMQP port (5672). The broker then answers the AMQP handshake with
// `Exception (501) Reason: "EOF"`. Only the port is inspected, so no credential can leak.
func withHint(
	rawURL string,
	err error,
) error {
	if err == nil {
		return nil
	}
	parsed, parseErr := url.Parse(rawURL)
	onMQTTPort := parseErr == nil && parsed.Port() == mqttPort
	if onMQTTPort || strings.Contains(err.Error(), "Exception (501)") {
		return fmt.Errorf(
			"%w (hint: AMQP_URL must use RabbitMQ's AMQP port 5672, not the MQTT plugin port 1883)",
			err,
		)
	}
	return err
}
