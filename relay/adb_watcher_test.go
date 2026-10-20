package relay

import (
	"testing"
	"time"
)

func TestParseAdbDevicesFiltersNonReadyStates(t *testing.T) {
	out := []byte(
		"List of devices attached\n" +
			"fake-ready-1\tdevice\n" +
			"fake-unauthorized\tunauthorized\n" +
			"fake-offline\toffline\n" +
			"fake-ready-2\tdevice product:demo model:demo\n" +
			"malformed-line\n" +
			"fake-recovery\trecovery\n",
	)

	got := parseAdbDevices(out)
	if len(got) != 2 {
		t.Fatalf("parseAdbDevices returned %d devices, want 2: %v", len(got), got)
	}
	for _, serial := range []string{"fake-ready-1", "fake-ready-2"} {
		if _, ok := got[serial]; !ok {
			t.Fatalf("parseAdbDevices lost ready device %q: %v", serial, got)
		}
	}
	for _, serial := range []string{"fake-unauthorized", "fake-offline", "fake-recovery"} {
		if _, ok := got[serial]; ok {
			t.Fatalf("parseAdbDevices included non-ready device %q: %v", serial, got)
		}
	}
}

func TestParseAdbDevicesHandlesCRLF(t *testing.T) {
	out := []byte("List of devices attached\r\nfake-ready\tdevice\r\nfake-offline\toffline\r\n")
	got := parseAdbDevices(out)
	if len(got) != 1 {
		t.Fatalf("parseAdbDevices returned %d devices, want 1: %v", len(got), got)
	}
	if _, ok := got["fake-ready"]; !ok {
		t.Fatalf("parseAdbDevices lost CRLF ready device: %v", got)
	}
}

func TestAdbBackoffIsBounded(t *testing.T) {
	tests := []struct {
		attempt int
		want    time.Duration
	}{
		{0, 500 * time.Millisecond},
		{1, 500 * time.Millisecond},
		{2, 1 * time.Second},
		{3, 2 * time.Second},
		{4, 4 * time.Second},
		{5, 8 * time.Second},
		{6, 8 * time.Second},
		{100, 8 * time.Second},
	}
	for _, tc := range tests {
		if got := adbBackoff(tc.attempt); got != tc.want {
			t.Errorf("adbBackoff(%d) = %s, want %s", tc.attempt, got, tc.want)
		}
	}
}
