package relay

import "testing"

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
