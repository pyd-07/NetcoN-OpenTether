package relay

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	adbPollInterval = 1 * time.Second
	adbBackoffMin   = 500 * time.Millisecond
	adbBackoffMax   = 8 * time.Second
	adbMaxAttempts  = 5

	// adbEmptyWarnThreshold is the number of consecutive `adb devices` polls
	// with no ready device before the watcher emits a warning (~10s at the
	// poll interval), giving a freshly plugged or booting device time to
	// appear before anything is reported.
	adbEmptyWarnThreshold = 10
	// adbEmptyWarnRepeat re-warns every N consecutive empty polls after the
	// threshold so a persistently empty server stays diagnosable without
	// flooding the log once per second.
	adbEmptyWarnRepeat = 120
	// adbFailWarnRepeat re-warns every N consecutive `adb devices` execution
	// failures after the first one (the first failure is always reported).
	adbFailWarnRepeat = 60
)

// AdbWatcher polls adb for ready devices and maintains one reverse tunnel
// setup per serial. Detection is explicit about device lifecycle so a USB
// unplug/replug is treated as a new connection rather than a stale session.
type AdbWatcher struct {
	port    int
	adbBin  string
	adbPort int
	mu      sync.Mutex
	known   map[string]struct{}
	states  map[string]*ConnectionStateTracker

	// Counters below are only touched by poll, which runs serially in Watch
	// (or directly from tests), so they need no additional synchronisation.
	emptyPolls     int // consecutive `adb devices` polls with no ready device
	pollFailStreak int // consecutive failed `adb devices` executions
}

func NewAdbWatcher(port int, adbBin string, adbPort int) *AdbWatcher {
	if adbBin == "" {
		adbBin = "adb"
	}
	return &AdbWatcher{
		port:    port,
		adbBin:  adbBin,
		adbPort: adbPort,
		known:   make(map[string]struct{}),
		states:  make(map[string]*ConnectionStateTracker),
	}
}

// adbCommandArgs builds the argument list for one watcher-owned ADB command.
// Every ADB invocation the watcher performs goes through this helper so the
// configured server port is applied consistently. adbPort == 0 keeps the ADB
// client's default server port.
func (w *AdbWatcher) adbCommandArgs(args ...string) []string {
	if w.adbPort == 0 {
		return args
	}
	return append([]string{"-P", strconv.Itoa(w.adbPort)}, args...)
}

// adbCommand runs one watcher-owned ADB command against the configured
// executable and server port, returning its combined output.
func (w *AdbWatcher) adbCommand(args ...string) ([]byte, error) {
	return exec.Command(w.adbBin, w.adbCommandArgs(args...)...).CombinedOutput()
}

// parseAdbDevices returns the serials whose adb state is exactly "device".
// The first line is the adb header and is skipped. Unauthorized, offline, and
// malformed lines are intentionally ignored.
func parseAdbDevices(out []byte) map[string]struct{} {
	current := make(map[string]struct{})
	scanner := bufio.NewScanner(bytes.NewReader(out))
	scanner.Scan()

	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 || fields[1] != "device" {
			continue
		}
		current[fields[0]] = struct{}{}
	}
	return current
}

// adbBackoff returns the bounded exponential delay for a one-based attempt.
// The sequence is 500ms, 1s, 2s, 4s, 8s, then 8s thereafter.
func adbBackoff(attempt int) time.Duration {
	backoff := adbBackoffMin
	for i := 1; i < attempt; i++ {
		backoff *= 2
		if backoff >= adbBackoffMax {
			return adbBackoffMax
		}
	}
	return backoff
}

func (w *AdbWatcher) Watch(ctx context.Context) {
	w.poll(ctx)

	ticker := time.NewTicker(adbPollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			w.mu.Lock()
			for _, tracker := range w.states {
				tracker.Set(StateStopping)
			}
			w.mu.Unlock()
			return
		case <-ticker.C:
			w.poll(ctx)
		}
	}
}

func (w *AdbWatcher) poll(ctx context.Context) {
	if ctx.Err() != nil {
		return
	}

	out, err := w.adbCommand("devices")
	if err != nil {
		w.pollFailStreak++
		// The first failure is always actionable; afterwards re-warn only
		// every adbFailWarnRepeat polls so a broken setup does not flood
		// the log once per polling interval.
		if w.pollFailStreak == 1 || w.pollFailStreak%adbFailWarnRepeat == 0 {
			warnf("adb watcher: %s devices failed (%d consecutive failures): %v — %s",
				w.adbBin, w.pollFailStreak, err, strings.TrimSpace(string(out)))
		}
		return
	}
	w.pollFailStreak = 0

	current := parseAdbDevices(out)
	if len(current) == 0 {
		w.emptyPolls++
		if w.emptyPolls == adbEmptyWarnThreshold || w.emptyPolls%adbEmptyWarnRepeat == 0 {
			warnf("adb watcher: ADB reports no ready devices after %d consecutive polls — no device in state \"device\" has appeared on this server; check the device authorization state and the configured executable/server port (--adb-bin, --adb-port)",
				w.emptyPolls)
		}
	} else {
		w.emptyPolls = 0
	}
	for serial := range current {
		w.mu.Lock()
		tracker := w.states[serial]
		if tracker == nil {
			tracker = NewConnectionStateTracker()
			w.states[serial] = tracker
		}
		_, alreadyKnown := w.known[serial]
		if !alreadyKnown {
			w.known[serial] = struct{}{}
			tracker.Set(StateDetecting)
		}
		w.mu.Unlock()

		if !alreadyKnown {
			logf("adb watcher: device detected [%s]", serial)
			go w.setupReverse(ctx, serial, tracker)
		}
	}

	w.mu.Lock()
	for serial := range w.known {
		if _, stillPresent := current[serial]; !stillPresent {
			if tracker := w.states[serial]; tracker != nil {
				tracker.Set(StateDisconnected)
			}
			logf("adb watcher: device disconnected [%s]", serial)
			delete(w.known, serial)
		}
	}
	w.mu.Unlock()
}

func (w *AdbWatcher) setupReverse(ctx context.Context, serial string, tracker *ConnectionStateTracker) {
	tracker.Set(StateConnecting)
	portStr := fmt.Sprintf("tcp:%d", w.port)
	args := []string{"-s", serial, "reverse", portStr, portStr}

	for attempt := 1; attempt <= adbMaxAttempts; attempt++ {
		out, err := w.adbCommand(args...)
		if err == nil {
			tracker.Set(StateConnected)
			logf("adb watcher: reverse tunnel ready for [%s] on port %d", serial, w.port)
			return
		}

		backoff := adbBackoff(attempt)
		debugf("adb watcher: reverse attempt %d/%d for [%s] failed: %v — %s; retrying in %s",
			attempt, adbMaxAttempts, serial, err, strings.TrimSpace(string(out)), backoff)

		timer := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			timer.Stop()
			tracker.Set(StateStopping)
			return
		case <-timer.C:
		}
	}

	lastErr := fmt.Errorf("adb reverse failed after %d attempts", adbMaxAttempts)
	tracker.Fail(lastErr)
	errorf("adb watcher: [%s]: %v", serial, lastErr)

	w.mu.Lock()
	delete(w.known, serial)
	w.mu.Unlock()
}
