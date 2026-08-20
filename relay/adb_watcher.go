package relay

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"os/exec"
	"strings"
	"sync"
	"time"
)

const (
	adbPollInterval = 1 * time.Second
	adbBackoffMin   = 500 * time.Millisecond
	adbBackoffMax   = 8 * time.Second
	adbMaxAttempts  = 5
)

// AdbWatcher polls adb for ready devices and maintains one reverse tunnel
// setup per serial. Detection is explicit about device lifecycle so a USB
// unplug/replug is treated as a new connection rather than a stale session.
type AdbWatcher struct {
	port   int
	mu     sync.Mutex
	known  map[string]struct{}
	states map[string]*ConnectionStateTracker
}

func NewAdbWatcher(port int) *AdbWatcher {
	return &AdbWatcher{
		port:   port,
		known:  make(map[string]struct{}),
		states: make(map[string]*ConnectionStateTracker),
	}
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

	out, err := exec.Command("adb", "devices").CombinedOutput()
	if err != nil {
		debugf("adb watcher: devices poll failed: %v — %s", err, strings.TrimSpace(string(out)))
		return
	}

	current := make(map[string]struct{})
	scanner := bufio.NewScanner(bytes.NewReader(out))
	scanner.Scan()

	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 {
			continue
		}
		serial, state := fields[0], fields[1]
		if state != "device" {
			continue
		}
		current[serial] = struct{}{}

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
		out, err := exec.Command("adb", args...).CombinedOutput()
		if err == nil {
			tracker.Set(StateConnected)
			logf("adb watcher: reverse tunnel ready for [%s] on port %d", serial, w.port)
			return
		}

		backoff := adbBackoffMin << (attempt - 1)
		if backoff > adbBackoffMax {
			backoff = adbBackoffMax
		}
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
