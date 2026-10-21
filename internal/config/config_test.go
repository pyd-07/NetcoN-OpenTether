package config

import (
	"strings"
	"testing"
)

func testValidConfig() Config {
	return Config{
		ListenAddr: "127.0.0.1:8765",
		TunName:    "ot0",
		TunAddr:    "10.0.0.2",
		TunPrefix:  24,
		AndroidIP:  "10.0.0.1",
		MTU:        1400,
	}
}

func TestConfigValidateAcceptsDefaults(t *testing.T) {
	cfg := testValidConfig()
	if err := cfg.Validate(); err != nil {
		t.Fatalf("default config rejected: %v", err)
	}
}

func TestConfigValidateListener(t *testing.T) {
	tests := []struct {
		name    string
		listen  string
		aoa     bool
		wantErr string
	}{
		{"IPv4 loopback", "127.0.0.1:8765", false, ""},
		{"alternate IPv4 loopback", "127.0.0.2:1", false, ""},
		{"IPv6 loopback", "[::1]:8765", false, ""},
		{"empty TCP listener", "", false, "required in TCP mode"},
		{"empty AOA listener", "", true, ""},
		{"wildcard port only", ":8765", false, "explicit loopback IP"},
		{"IPv4 wildcard", "0.0.0.0:8765", false, "not a loopback"},
		{"IPv6 wildcard", "[::]:8765", false, "not a loopback"},
		{"LAN address", "192.168.1.10:8765", false, "not a loopback"},
		{"public address", "8.8.8.8:8765", false, "not a loopback"},
		{"hostname", "localhost:8765", false, "IP literal"},
		{"missing port", "127.0.0.1", false, "invalid listen address"},
		{"port zero", "127.0.0.1:0", false, "invalid listen port"},
		{"port too high", "127.0.0.1:70000", false, "invalid listen port"},
		{"non-numeric port", "127.0.0.1:http", false, "invalid listen port"},
		{"IPv4-mapped IPv6", "[::ffff:127.0.0.1]:8765", false, "IPv4-mapped"},
		{"AOA rejects wildcard", "0.0.0.0:8765", true, "not a loopback"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cfg := testValidConfig()
			cfg.ListenAddr = tc.listen
			cfg.AOAMode = tc.aoa

			err := cfg.Validate()
			if tc.wantErr == "" {
				if err != nil {
					t.Fatalf("config rejected: %v", err)
				}
				return
			}
			if err == nil {
				t.Fatal("expected an error, got nil")
			}
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("error %q does not contain %q", err, tc.wantErr)
			}
		})
	}
}

func TestConfigValidateAdbPort(t *testing.T) {
	tests := []struct {
		name    string
		port    int
		wantErr string
	}{
		{"zero keeps ADB client default", 0, ""},
		{"non-default server port", 5038, ""},
		{"negative port", -1, "adb server port"},
		{"port above range", 65536, "adb server port"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cfg := testValidConfig()
			cfg.AdbPort = tc.port
			err := cfg.Validate()
			if tc.wantErr == "" {
				if err != nil {
					t.Fatalf("AdbPort %d rejected: %v", tc.port, err)
				}
				return
			}
			if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("AdbPort %d: err = %v, want error containing %q", tc.port, err, tc.wantErr)
			}
		})
	}
}

func TestConfigValidateAllowsEmptyListenAddrInAOAMode(t *testing.T) {
	cfg := testValidConfig()
	cfg.AOAMode = true
	cfg.ListenAddr = ""

	if err := cfg.Validate(); err != nil {
		t.Fatalf("AOA config with no TCP listener rejected: %v", err)
	}
}

func TestConfigValidateRejectsEmptyListenAddrInTCPMode(t *testing.T) {
	cfg := testValidConfig()
	cfg.ListenAddr = ""

	err := cfg.Validate()
	if err == nil {
		t.Fatal("expected an error, got nil")
	}
	if !strings.Contains(err.Error(), "required in TCP mode") {
		t.Fatalf("error %q does not explain that TCP mode needs a listener", err)
	}
}

func TestConfigValidateTunnelPrefix(t *testing.T) {
	tests := []struct {
		name    string
		prefix  int
		wantErr string
	}{
		{"minimum supported /24", 24, ""},
		{"intermediate /25", 25, ""},
		{"point-to-point /30", 30, ""},
		{"default route /0", 0, "prefix"},
		{"too broad /16", 16, "prefix"},
		{"too broad /23", 23, "prefix"},
		{"too narrow /31", 31, "prefix"},
		{"host /32", 32, "prefix"},
		{"out of range /33", 33, "prefix"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cfg := testValidConfig()
			cfg.TunPrefix = tc.prefix

			err := cfg.Validate()
			if tc.wantErr == "" {
				if err != nil {
					t.Fatalf("config rejected: %v", err)
				}
				return
			}
			if err == nil {
				t.Fatal("expected an error, got nil")
			}
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("error %q does not contain %q", err, tc.wantErr)
			}
		})
	}
}

func TestConfigValidateRejectsUnsafeEndpoints(t *testing.T) {
	tests := []struct {
		name    string
		android bool
		value   string
		wantErr string
	}{
		{"TUN unspecified", false, "0.0.0.0", "unspecified"},
		{"Android unspecified", true, "0.0.0.0", "unspecified"},
		{"TUN loopback", false, "127.0.0.1", "loopback"},
		{"TUN multicast", false, "224.0.0.1", "multicast"},
		{"TUN broadcast", false, "255.255.255.255", "broadcast"},
		{"TUN link-local", false, "169.254.1.1", "link-local"},
		{"TUN IPv4-mapped", false, "::ffff:10.0.0.2", "IPv4-mapped"},
		{"TUN IPv6", false, "fd00::2", "IPv4 address"},
		{"TUN invalid", false, "10.0.0.999", "valid IPv4"},
		{"Android invalid", true, "not-an-ip", "valid IPv4"},
		{"TUN network address", false, "10.0.0.0", "network or broadcast"},
		{"TUN broadcast address", false, "10.0.0.255", "network or broadcast"},
		{"Android network address", true, "10.0.0.0", "network or broadcast"},
		{"Android broadcast address", true, "10.0.0.255", "network or broadcast"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cfg := testValidConfig()
			if tc.android {
				cfg.AndroidIP = tc.value
			} else {
				cfg.TunAddr = tc.value
			}

			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected an error, got nil")
			}
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("error %q does not contain %q", err, tc.wantErr)
			}
		})
	}
}

func TestConfigValidateRejectsEqualEndpoints(t *testing.T) {
	cfg := testValidConfig()
	cfg.AndroidIP = cfg.TunAddr

	err := cfg.Validate()
	if err == nil {
		t.Fatal("expected an error, got nil")
	}
	if !strings.Contains(err.Error(), "must differ") {
		t.Fatalf("error %q does not mention differing endpoints", err)
	}
}

func TestConfigValidateRejectsIncompatibleSubnet(t *testing.T) {
	tests := []struct {
		name    string
		prefix  int
		tun     string
		android string
		wantErr string
	}{
		{"outside /24", 24, "10.0.0.2", "10.0.1.1", "not in the TUN subnet"},
		{"outside /30", 30, "10.0.0.2", "10.0.0.4", "not in the TUN subnet"},
		{"network address /30", 30, "10.0.0.0", "10.0.0.1", "network or broadcast"},
		{"broadcast address /30", 30, "10.0.0.2", "10.0.0.3", "network or broadcast"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cfg := testValidConfig()
			cfg.TunPrefix = tc.prefix
			cfg.TunAddr = tc.tun
			cfg.AndroidIP = tc.android

			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected an error, got nil")
			}
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("error %q does not contain %q", err, tc.wantErr)
			}
		})
	}
}

func TestConfigValidateMTUBoundaries(t *testing.T) {
	tests := []struct {
		name    string
		mtu     int
		wantErr string
	}{
		{"zero", 0, "MTU"},
		{"below IPv6 minimum", 1279, "MTU"},
		{"IPv6 minimum", 1280, ""},
		{"stock client MTU", 1400, ""},
		{"above client maximum", 1401, "MTU"},
		{"old relay default", 1500, "MTU"},
		{"protocol maximum", 65535, "MTU"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cfg := testValidConfig()
			cfg.MTU = tc.mtu

			err := cfg.Validate()
			if tc.wantErr == "" {
				if err != nil {
					t.Fatalf("config rejected: %v", err)
				}
				return
			}
			if err == nil {
				t.Fatal("expected an error, got nil")
			}
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("error %q does not contain %q", err, tc.wantErr)
			}
		})
	}
}

func TestConfigValidateNormalizesEndpoints(t *testing.T) {
	cfg := testValidConfig()
	cfg.TunAddr = "10.0.0.2"
	cfg.AndroidIP = "10.0.0.1"

	if err := cfg.Validate(); err != nil {
		t.Fatalf("config rejected: %v", err)
	}
	if cfg.TunAddr != "10.0.0.2" {
		t.Fatalf("TUN address = %q, want canonical 10.0.0.2", cfg.TunAddr)
	}
	if cfg.AndroidIP != "10.0.0.1" {
		t.Fatalf("Android address = %q, want canonical 10.0.0.1", cfg.AndroidIP)
	}
}

func TestConfigValidateTUNName(t *testing.T) {
	tests := []struct {
		name    string
		tunName string
		wantErr string
	}{
		{"empty", "", "TUN name"},
		{"too long", "abcdefghijklmnop", "TUN name"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cfg := testValidConfig()
			cfg.TunName = tc.tunName

			err := cfg.Validate()
			if err == nil {
				t.Fatal("expected an error, got nil")
			}
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("error %q does not contain %q", err, tc.wantErr)
			}
		})
	}
}
