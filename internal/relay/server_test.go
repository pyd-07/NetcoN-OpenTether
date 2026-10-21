package relay

import (
	"strings"
	"testing"

	"github.com/pyd-07/opentether/internal/config"
)

func TestListenPort(t *testing.T) {
	tests := []struct {
		name string
		addr string
		want int
	}{
		{"IPv4 default", "127.0.0.1:8765", 8765},
		{"IPv6 loopback", "[::1]:9000", 9000},
		{"maximum valid", "127.0.0.1:65535", 65535},
		{"zero falls back", "127.0.0.1:0", 8765},
		{"out of range falls back", "127.0.0.1:65536", 8765},
		{"non-numeric falls back", "127.0.0.1:not-a-port", 8765},
		{"malformed address falls back", "not-an-address", 8765},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := listenPort(tc.addr); got != tc.want {
				t.Fatalf("listenPort(%q) = %d, want %d", tc.addr, got, tc.want)
			}
		})
	}
}

// NewServer must reject a bad config before opening /dev/net/tun. This test
// runs unprivileged in CI and therefore also proves the validation ordering.
func TestNewServerRejectsInvalidConfigBeforeTUN(t *testing.T) {
	_, err := NewServer(config.Config{})
	if err == nil {
		t.Fatal("expected an error, got nil")
	}
	if !strings.Contains(err.Error(), "invalid config") {
		t.Fatalf("error = %q, want an invalid config error before TUN setup", err)
	}
	if strings.Contains(err.Error(), "/dev/net/tun") {
		t.Fatalf("config was validated after touching TUN: %v", err)
	}
}

// Config.Validate permits an empty listener in AOA mode because RunAOA does
// not bind TCP. NewServer is TCP-only, so it must reject AOAMode rather than
// reaching net.Listen("tcp", "") and exposing a wildcard listener.
func TestNewServerRejectsAOAConfigBeforeTUN(t *testing.T) {
	cfg := testValidConfig()
	cfg.AOAMode = true
	cfg.ListenAddr = ""

	_, err := NewServer(cfg)
	if err == nil {
		t.Fatal("expected an error, got nil")
	}
	if !strings.Contains(err.Error(), "RunAOA") {
		t.Fatalf("error = %q, want a TCP-only/RunAOA error before TUN setup", err)
	}
	if strings.Contains(err.Error(), "/dev/net/tun") {
		t.Fatalf("config was processed after touching TUN: %v", err)
	}
}
