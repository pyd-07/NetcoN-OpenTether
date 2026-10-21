package relay

import (
	"testing"
	"time"

	"github.com/pyd-07/opentether/internal/config"
)

func testValidConfig() config.Config {
	return config.Config{
		ListenAddr: "127.0.0.1:8765",
		TunName:    "ot0",
		TunAddr:    "10.0.0.2",
		TunPrefix:  24,
		AndroidIP:  "10.0.0.1",
		MTU:        1400,
	}
}

func waitForCondition(t *testing.T, what string, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}
