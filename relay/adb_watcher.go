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

// AdbWatcher polls `adb devices` every two seconds and automatically runs
// `adb reverse tcp:PORT tcp:PORT` whenever a new device appears in "device"
// state. This eliminates the manual setup step.
//
// On reconnect (cable unplug + replug), the serial either stays the same
// or changes. Either way, we detect the "device" state reappearing and
// re-run the tunnel setup — so reconnects are fully automatic.
type AdbWatcher struct {
	port int

	mu    sync.Mutex
	known map[string]struct{} // serials for which reverse setup is active or succeeded
}

// NewAdbWatcher returns a watcher that will forward port on any Android
// device that appears while ctx is live.
func NewAdbWatcher(port int) *AdbWatcher {
	return &AdbWatcher{
		port:  port,
		known: make(map[string]struct{}),
	}
}

// Watch polls forever until ctx is cancelled. Run it in a goroutine.
func (w *AdbWatcher) Watch(ctx context.Context) {
	// Poll immediately so we catch a device already connected before relay started.
	w.poll()

	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			w.poll()
		}
	}
}

// poll runs `adb devices` once and dispatches setupReverse for each new device.
func (w *AdbWatcher) poll() {
	out, err := exec.Command("adb", "devices").Output()
	if err != nil {
		// adb may not be installed, or the daemon isn't running yet — not fatal.
		debugf("adb watcher: devices poll failed: %v", err)
		return
	}

	current := make(map[string]struct{})
	scanner := bufio.NewScanner(bytes.NewReader(out))
	scanner.Scan() // discard "List of devices attached" header

	for scanner.Scan() {
		line := scanner.Text()
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		serial, state := fields[0], fields[1]
		if state != "device" {
			// "offline" / "unauthorized" / "recovery" — not ready
			continue
		}
		current[serial] = struct{}{}

		w.mu.Lock()
		_, alreadyKnown := w.known[serial]
		if !alreadyKnown {
			// Reserve serial immediately before spawning the goroutine.
			// Without this, a second poll() 2 seconds later would also see
			// the serial not in known and spawn a duplicate goroutine.
			w.known[serial] = struct{}{}
		}
		w.mu.Unlock()

		if !alreadyKnown {
			logf("adb watcher: device connected [%s] — setting up reverse tunnel", serial)
			go w.setupReverse(serial)
		}
	}

	// Forget serials that have gone away so a future replug triggers re-setup.
	w.mu.Lock()
	for serial := range w.known {
		if _, stillPresent := current[serial]; !stillPresent {
			logf("adb watcher: device disconnected [%s]", serial)
			delete(w.known, serial)
		}
	}
	w.mu.Unlock()
}

// setupReverse runs `adb -s <serial> reverse tcp:PORT tcp:PORT` with up to
// 4 attempts, backing off 500 ms × attempt between tries.
//
// On success: serial stays in known (added by poll before this goroutine ran).
// On failure: serial is removed from known so the next poll can retry.
func (w *AdbWatcher) setupReverse(serial string) {
	portStr := fmt.Sprintf("tcp:%d", w.port)
	args := []string{"-s", serial, "reverse", portStr, portStr}

	for attempt := 1; attempt <= 4; attempt++ {
		out, err := exec.Command("adb", args...).CombinedOutput()
		if err == nil {
			logf("adb watcher: reverse tunnel ready for [%s] on port %d", serial, w.port)
			return // serial already in known from poll(); nothing more to do
		}
		debugf("adb reverse attempt %d for [%s]: %v — %s",
			attempt, serial, err, strings.TrimSpace(string(out)))
		time.Sleep(time.Duration(attempt) * 500 * time.Millisecond)
	}

	errorf("adb watcher: giving up on reverse tunnel for [%s] after 4 attempts", serial)
	// Remove serial so the next poll() can retry when the device reconnects.
	w.mu.Lock()
	delete(w.known, serial)
	w.mu.Unlock()
}
