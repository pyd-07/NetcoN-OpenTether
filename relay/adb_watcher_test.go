package relay

import (
	"bytes"
	"context"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"
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

// ── configurable executable / server port ───────────────────────────

func TestAdbCommandArgsOmitsPortWhenDefault(t *testing.T) {
	w := NewAdbWatcher(8765, "", 0)
	for _, args := range [][]string{
		{"devices"},
		{"-s", "stub-device", "reverse", "tcp:8765", "tcp:8765"},
	} {
		got := w.adbCommandArgs(args...)
		if len(got) != len(args) {
			t.Fatalf("adbCommandArgs(%v) = %v, want unchanged", args, got)
		}
		for i := range args {
			if got[i] != args[i] {
				t.Fatalf("adbCommandArgs(%v) = %v, want unchanged", args, got)
			}
		}
	}
}

func TestAdbCommandArgsPrependsExplicitPort(t *testing.T) {
	w := NewAdbWatcher(8765, "", 5038)
	tests := []struct {
		args []string
		want []string
	}{
		{[]string{"devices"}, []string{"-P", "5038", "devices"}},
		{[]string{"-s", "stub-device", "reverse", "tcp:8765", "tcp:8765"},
			[]string{"-P", "5038", "-s", "stub-device", "reverse", "tcp:8765", "tcp:8765"}},
	}
	for _, tc := range tests {
		got := w.adbCommandArgs(tc.args...)
		if len(got) != len(tc.want) {
			t.Fatalf("adbCommandArgs(%v) = %v, want %v", tc.args, got, tc.want)
		}
		for i := range tc.want {
			if got[i] != tc.want[i] {
				t.Fatalf("adbCommandArgs(%v) = %v, want %v", tc.args, got, tc.want)
			}
		}
	}
}

func TestNewAdbWatcherDefaultsBlankExecutable(t *testing.T) {
	w := NewAdbWatcher(8765, "", 5038)
	if w.adbBin != "adb" {
		t.Fatalf("blank adbBin = %q, want \"adb\"", w.adbBin)
	}
}

// ── stub ADB executable ─────────────────────────────────────────────

// writeAdbStub creates a POSIX shell script that pretends to be adb. It
// appends every invocation's arguments to logPath and answers `devices` and
// `reverse` according to the content of modePath: "device" reports a ready
// device, anything else reports an empty list, and "fail-reverse" makes the
// reverse command exit nonzero.
func writeAdbStub(t *testing.T, logPath, modePath string) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("adb stub tests require a POSIX shell")
	}
	script := "#!/bin/sh\n" +
		"printf '%s\\n' \"$*\" >> \"" + logPath + "\"\n" +
		"if [ \"$1\" = \"-P\" ]; then shift 2; fi\n" +
		"if [ \"$1\" = \"-s\" ]; then shift 2; fi\n" +
		"mode=$(cat \"" + modePath + "\")\n" +
		"case \"$1\" in\n" +
		"devices)\n" +
		"  if [ \"$mode\" = \"device\" ]; then\n" +
		"    printf 'List of devices attached\\nstub-device\\tdevice\\n'\n" +
		"  else\n" +
		"    printf 'List of devices attached\\n'\n" +
		"  fi\n" +
		"  exit 0\n" +
		"  ;;\n" +
		"reverse)\n" +
		"  if [ \"$mode\" = \"fail-reverse\" ]; then\n" +
		"    printf 'adb: reverse failed\\n'\n" +
		"    exit 1\n" +
		"  fi\n" +
		"  exit 0\n" +
		"  ;;\n" +
		"esac\n" +
		"exit 0\n"
	bin := filepath.Join(t.TempDir(), "adb-stub")
	if err := os.WriteFile(bin, []byte(script), 0o755); err != nil {
		t.Fatalf("write stub: %v", err)
	}
	return bin
}

func setStubMode(t *testing.T, modePath, mode string) {
	t.Helper()
	if err := os.WriteFile(modePath, []byte(mode), 0o644); err != nil {
		t.Fatalf("write mode: %v", err)
	}
}

func captureLogs(t *testing.T) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	log.SetOutput(&buf)
	t.Cleanup(func() { log.SetOutput(os.Stderr) })
	return &buf
}

func waitForTrackerState(t *testing.T, tracker *ConnectionStateTracker, want ConnectionState) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		state, _ := tracker.Snapshot()
		if state == want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	state, err := tracker.Snapshot()
	t.Fatalf("tracker state = %v (%v), want %v", state, err, want)
}

// ── stub-driven behaviour ───────────────────────────────────────────

func TestSetupReverseTargetsConfiguredServerAndPort(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "adb.log")
	modePath := filepath.Join(dir, "mode")
	setStubMode(t, modePath, "device")
	w := NewAdbWatcher(8765, writeAdbStub(t, logPath, modePath), 5038)

	tracker := NewConnectionStateTracker()
	w.setupReverse(context.Background(), "stub-device", tracker)

	state, err := tracker.Snapshot()
	if state != StateConnected || err != nil {
		t.Fatalf("tracker = %v (%v), want connected without error", state, err)
	}
	logged, readErr := os.ReadFile(logPath)
	if readErr != nil {
		t.Fatalf("read stub log: %v", readErr)
	}
	want := "-P 5038 -s stub-device reverse tcp:8765 tcp:8765"
	if !strings.Contains(string(logged), want) {
		t.Fatalf("stub log = %q, want line containing %q", logged, want)
	}
}

func TestSetupReverseDefaultPortOmitsPFlag(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "adb.log")
	modePath := filepath.Join(dir, "mode")
	setStubMode(t, modePath, "device")
	w := NewAdbWatcher(8765, writeAdbStub(t, logPath, modePath), 0)

	tracker := NewConnectionStateTracker()
	w.setupReverse(context.Background(), "stub-device", tracker)

	state, _ := tracker.Snapshot()
	if state != StateConnected {
		t.Fatalf("tracker state = %v, want connected", state)
	}
	logged, _ := os.ReadFile(logPath)
	want := "-s stub-device reverse tcp:8765 tcp:8765"
	if !strings.Contains(string(logged), want) {
		t.Fatalf("stub log = %q, want line containing %q", logged, want)
	}
	if strings.Contains(string(logged), "-P") {
		t.Fatalf("stub log = %q, must not contain -P for default server port", logged)
	}
}

func TestSetupReverseFailureRetriesThenFails(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "adb.log")
	modePath := filepath.Join(dir, "mode")
	setStubMode(t, modePath, "fail-reverse")
	w := NewAdbWatcher(8765, writeAdbStub(t, logPath, modePath), 5038)

	buf := captureLogs(t)
	tracker := NewConnectionStateTracker()
	w.setupReverse(context.Background(), "stub-device", tracker)

	state, err := tracker.Snapshot()
	if state != StateFailed {
		t.Fatalf("tracker state = %v (%v), want failed", state, err)
	}
	if err == nil || !strings.Contains(err.Error(), "after 5 attempts") {
		t.Fatalf("tracker err = %v, want mention of 5 attempts", err)
	}
	logged, _ := os.ReadFile(logPath)
	if got := strings.Count(string(logged), "reverse tcp:8765 tcp:8765"); got != adbMaxAttempts {
		t.Fatalf("stub saw %d reverse attempts, want %d", got, adbMaxAttempts)
	}
	if !strings.Contains(buf.String(), "reverse failed after 5 attempts") {
		t.Fatalf("log = %q, want always-on final failure", buf.String())
	}
}

func TestPollDiscoversDeviceAndSetsUpReverse(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "adb.log")
	modePath := filepath.Join(dir, "mode")
	setStubMode(t, modePath, "device")
	w := NewAdbWatcher(8765, writeAdbStub(t, logPath, modePath), 5038)

	w.poll(context.Background())

	if _, ok := w.known["stub-device"]; !ok {
		t.Fatalf("known = %v, want stub-device detected", w.known)
	}
	waitForTrackerState(t, w.states["stub-device"], StateConnected)
	logged, _ := os.ReadFile(logPath)
	if !strings.Contains(string(logged), "-P 5038 devices") {
		t.Fatalf("stub log = %q, want discovery via -P 5038 devices", logged)
	}
	if !strings.Contains(string(logged), "-P 5038 -s stub-device reverse tcp:8765 tcp:8765") {
		t.Fatalf("stub log = %q, want reverse via configured port", logged)
	}
}

func TestPollEmptyDeviceListWarnsAtThresholdAndRateLimits(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "adb.log")
	modePath := filepath.Join(dir, "mode")
	setStubMode(t, modePath, "empty")
	w := NewAdbWatcher(8765, writeAdbStub(t, logPath, modePath), 0)

	buf := captureLogs(t)
	for i := 0; i < adbEmptyWarnThreshold-1; i++ {
		w.poll(context.Background())
	}
	if got := strings.Count(buf.String(), "no ready devices"); got != 0 {
		t.Fatalf("warned %d times before threshold: %q", got, buf.String())
	}
	w.poll(context.Background())
	if got := strings.Count(buf.String(), "no ready devices"); got != 1 {
		t.Fatalf("warn count at threshold = %d, want 1: %q", got, buf.String())
	}

	buf.Reset()
	for i := 0; i < adbEmptyWarnRepeat-adbEmptyWarnThreshold-1; i++ {
		w.poll(context.Background())
	}
	if got := strings.Count(buf.String(), "no ready devices"); got != 0 {
		t.Fatalf("warned %d times before repeat cadence: %q", got, buf.String())
	}
	w.poll(context.Background())
	if got := strings.Count(buf.String(), "no ready devices"); got != 1 {
		t.Fatalf("warn count at repeat = %d, want 1: %q", got, buf.String())
	}

	// A visible device resets the counter, so the next empty streak must
	// pass the full threshold again before warning.
	setStubMode(t, modePath, "device")
	w.poll(context.Background())
	if w.emptyPolls != 0 {
		t.Fatalf("emptyPolls = %d after a visible device, want 0", w.emptyPolls)
	}
	buf.Reset()
	setStubMode(t, modePath, "empty")
	for i := 0; i < adbEmptyWarnThreshold; i++ {
		w.poll(context.Background())
	}
	if got := strings.Count(buf.String(), "no ready devices"); got != 1 {
		t.Fatalf("warn count after reset streak = %d, want 1: %q", got, buf.String())
	}
}

func TestPollExecFailureWarnsOnceThenRateLimits(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "no-such-adb")
	w := NewAdbWatcher(8765, missing, 0)

	buf := captureLogs(t)
	w.poll(context.Background())
	if got := strings.Count(buf.String(), "devices failed"); got != 1 {
		t.Fatalf("warn count after first failure = %d, want 1: %q", got, buf.String())
	}
	if !strings.Contains(buf.String(), missing) {
		t.Fatalf("warn must identify the executable, got: %q", buf.String())
	}

	buf.Reset()
	for i := 0; i < adbFailWarnRepeat-2; i++ {
		w.poll(context.Background())
	}
	if got := strings.Count(buf.String(), "devices failed"); got != 0 {
		t.Fatalf("warned %d times before repeat cadence: %q", got, buf.String())
	}
	w.poll(context.Background())
	if got := strings.Count(buf.String(), "devices failed"); got != 1 {
		t.Fatalf("warn count at repeat = %d, want 1: %q", got, buf.String())
	}
	if w.pollFailStreak != adbFailWarnRepeat {
		t.Fatalf("pollFailStreak = %d, want %d", w.pollFailStreak, adbFailWarnRepeat)
	}
}

func TestPollDisconnectClearsDeviceAndReconnectReadds(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "adb.log")
	modePath := filepath.Join(dir, "mode")
	setStubMode(t, modePath, "device")
	w := NewAdbWatcher(8765, writeAdbStub(t, logPath, modePath), 5038)

	w.poll(context.Background())
	waitForTrackerState(t, w.states["stub-device"], StateConnected)

	setStubMode(t, modePath, "empty")
	w.poll(context.Background())
	if _, ok := w.known["stub-device"]; ok {
		t.Fatal("stub-device still known after disappearing from the device list")
	}
	if state, _ := w.states["stub-device"].Snapshot(); state != StateDisconnected {
		t.Fatalf("tracker state = %v, want disconnected", state)
	}

	setStubMode(t, modePath, "device")
	w.poll(context.Background())
	if _, ok := w.known["stub-device"]; !ok {
		t.Fatal("stub-device not re-detected after reconnect")
	}
	waitForTrackerState(t, w.states["stub-device"], StateConnected)
	logged, _ := os.ReadFile(logPath)
	if got := strings.Count(string(logged), "reverse tcp:8765 tcp:8765"); got != 2 {
		t.Fatalf("stub saw %d reverse mappings, want 2 (one per connection)", got)
	}
}
