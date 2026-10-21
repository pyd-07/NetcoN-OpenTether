package adb

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

	"github.com/pyd-07/opentether/internal/connection"
	"github.com/pyd-07/opentether/internal/logging"
)

const (
	adbPollInterval = 1 * time.Second
	adbBackoffMin   = 500 * time.Millisecond
	adbBackoffMax   = 8 * time.Second
	adbMaxAttempts  = 5

	// adbCommandTimeout bounds every watcher-owned adb invocation. Without it
	// a hung adb server or a stalled Windows adb.exe under WSL can block the
	// poll loop and relay shutdown indefinitely.
	adbCommandTimeout = 10 * time.Second
	// adbShutdownTimeout bounds reverse-mapping removal during shutdown.
	adbShutdownTimeout = 15 * time.Second

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

// adbCommandRunner runs one fully-built adb argument list. Tests inject a fake
// runner so reverse-mapping ownership and shutdown behaviour can be exercised
// without a real adb server.
type adbCommandRunner func(ctx context.Context, args []string) ([]byte, error)

// AdbWatcher polls adb for ready devices and maintains one reverse tunnel
// setup per serial. Detection is explicit about device lifecycle so a USB
// unplug/replug is treated as a new connection rather than a stale session.
//
// Ownership: a reverse mapping is removed on shutdown only when this watcher
// observed it absent, created it, and still owns the device generation. A
// mapping that already existed (for example, created manually or by a previous
// process) is used but never removed.
type AdbWatcher struct {
	port           int
	adbBin         string
	adbPort        int
	commandTimeout time.Duration
	run            adbCommandRunner

	mu          sync.Mutex
	known       map[string]struct{}           // device currently in state "device"
	established map[string]struct{}           // reverse mapping believed ready
	owned       map[string]struct{}           // mapping created by this watcher
	inflight    map[string]struct{}           // setup goroutine currently running
	nextAttempt map[string]time.Time          // retry time after a failed setup
	generation  map[string]uint64             // invalidates stale setup completions
	cancels     map[string]context.CancelFunc // cancels a device's in-flight setup
	states      map[string]*connection.ConnectionStateTracker

	// setupWG tracks reverse-setup goroutines so shutdown can wait for them
	// before removing owned mappings or letting the process exit.
	setupWG sync.WaitGroup

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
		port:           port,
		adbBin:         adbBin,
		adbPort:        adbPort,
		commandTimeout: adbCommandTimeout,
		known:          make(map[string]struct{}),
		established:    make(map[string]struct{}),
		owned:          make(map[string]struct{}),
		inflight:       make(map[string]struct{}),
		nextAttempt:    make(map[string]time.Time),
		generation:     make(map[string]uint64),
		cancels:        make(map[string]context.CancelFunc),
		states:         make(map[string]*connection.ConnectionStateTracker),
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
// executable and server port, returning its combined output. Every command is
// bounded by commandTimeout and by the parent context.
func (w *AdbWatcher) adbCommand(ctx context.Context, args ...string) ([]byte, error) {
	full := w.adbCommandArgs(args...)

	timeout := w.commandTimeout
	if timeout <= 0 {
		timeout = adbCommandTimeout
	}
	cmdCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	if w.run != nil {
		return w.run(cmdCtx, full)
	}
	return exec.CommandContext(cmdCtx, w.adbBin, full...).CombinedOutput()
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

// parseAdbReverseList reports whether out contains the exact reverse mapping
// spec. adb prints one mapping per line, optionally prefixed by a device serial:
//
//	tcp:8765 tcp:8765
//	<serial> tcp:8765 tcp:8765
func parseAdbReverseList(out []byte, spec string) bool {
	scanner := bufio.NewScanner(bytes.NewReader(out))
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 {
			continue
		}
		if fields[len(fields)-2] == spec && fields[len(fields)-1] == spec {
			return true
		}
	}
	return false
}

// adbReverseAlreadyPresent recognises the adb error emitted when a mapping
// exists but could not be listed (for example, a transient `reverse --list`
// failure). Treating it as usable avoids tearing down working forwarding.
func adbReverseAlreadyPresent(out []byte) bool {
	msg := strings.ToLower(string(out))
	return strings.Contains(msg, "already in use") ||
		strings.Contains(msg, "already exists") ||
		strings.Contains(msg, "cannot rebind")
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
	// Always attempt ownership cleanup, including when startup polling is
	// cancelled before a first successful reverse setup.
	defer w.cleanupOwnedReverse()

	w.poll(ctx)

	ticker := time.NewTicker(adbPollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			w.mu.Lock()
			for _, tracker := range w.states {
				tracker.Set(connection.StateStopping)
			}
			w.mu.Unlock()

			// Stop device setup goroutines and wait for them so none can create
			// a mapping after cleanup took its ownership snapshot.
			w.cancelAllSetups()
			w.setupWG.Wait()
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

	out, err := w.adbCommand(ctx, "devices")
	if err != nil {
		w.pollFailStreak++
		// The first failure is always actionable; afterwards re-warn only
		// every adbFailWarnRepeat polls so a broken setup does not flood the
		// log once per polling interval.
		if w.pollFailStreak == 1 || w.pollFailStreak%adbFailWarnRepeat == 0 {
			logging.Warnf("adb watcher: %s devices failed (%d consecutive failures): %v — %s",
				w.adbBin, w.pollFailStreak, err, strings.TrimSpace(string(out)))
		}
		return
	}
	w.pollFailStreak = 0

	current := parseAdbDevices(out)
	if len(current) == 0 {
		w.emptyPolls++
		if w.emptyPolls == adbEmptyWarnThreshold || w.emptyPolls%adbEmptyWarnRepeat == 0 {
			logging.Warnf("adb watcher: ADB reports no ready devices after %d consecutive polls — no device in state \"device\" has appeared on this server; check the device authorization state and the configured executable/server port (--adb-bin, --adb-port)",
				w.emptyPolls)
		}
	} else {
		w.emptyPolls = 0
	}

	now := time.Now()
	for serial := range current {
		w.mu.Lock()
		tracker := w.states[serial]
		if tracker == nil {
			tracker = connection.NewConnectionStateTracker()
			w.states[serial] = tracker
		}

		_, alreadyKnown := w.known[serial]
		if !alreadyKnown {
			w.known[serial] = struct{}{}
			w.generation[serial]++
			delete(w.established, serial)
			delete(w.owned, serial)
			delete(w.nextAttempt, serial)
			tracker.Set(connection.StateDetecting)
		}

		_, established := w.established[serial]
		_, busy := w.inflight[serial]
		retryAt := w.nextAttempt[serial]
		shouldStart := !busy && !established &&
			(!alreadyKnown || retryAt.IsZero() || !now.Before(retryAt))

		gen := w.generation[serial]
		if shouldStart {
			w.inflight[serial] = struct{}{}
		}
		w.mu.Unlock()

		if shouldStart {
			if !alreadyKnown {
				logging.Logf("adb watcher: device detected [%s]", serial)
			}
			w.startSetup(ctx, serial, tracker, gen)
		}
	}

	w.mu.Lock()
	for serial := range w.known {
		if _, stillPresent := current[serial]; stillPresent {
			continue
		}
		w.generation[serial]++ // invalidate any setup that completes late
		if cancel := w.cancels[serial]; cancel != nil {
			cancel()
		}
		delete(w.cancels, serial)
		delete(w.inflight, serial)
		delete(w.established, serial)
		delete(w.owned, serial)
		delete(w.nextAttempt, serial)
		if tracker := w.states[serial]; tracker != nil {
			tracker.Set(connection.StateDisconnected)
		}
		delete(w.known, serial)
		logging.Logf("adb watcher: device disconnected [%s]", serial)
	}
	w.mu.Unlock()
}

// startSetup launches one reverse-setup goroutine for a device generation.
func (w *AdbWatcher) startSetup(ctx context.Context, serial string, tracker *connection.ConnectionStateTracker, gen uint64) {
	setupCtx, cancel := context.WithCancel(ctx)
	w.mu.Lock()
	w.cancels[serial] = cancel
	w.mu.Unlock()

	w.setupWG.Add(1)
	go func() {
		defer w.setupWG.Done()
		established, owned := w.setupReverse(setupCtx, serial, tracker)
		cancel()
		w.finishSetup(serial, gen, established, owned)
	}()
}

// finishSetup records a setup result only if it still belongs to the current
// device generation. Stale completions (unplug/replug) are ignored.
func (w *AdbWatcher) finishSetup(serial string, gen uint64, established, owned bool) {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.generation[serial] != gen {
		return
	}

	delete(w.inflight, serial)
	delete(w.cancels, serial)
	if established {
		w.established[serial] = struct{}{}
	} else {
		// Keep the device known but retry after the maximum backoff instead of
		// spawning a new goroutine on every one-second poll.
		w.nextAttempt[serial] = time.Now().Add(adbBackoffMax)
	}
	if owned {
		w.owned[serial] = struct{}{}
	}
}

func (w *AdbWatcher) cancelAllSetups() {
	w.mu.Lock()
	cancels := make([]context.CancelFunc, 0, len(w.cancels))
	for _, cancel := range w.cancels {
		cancels = append(cancels, cancel)
	}
	w.mu.Unlock()
	for _, cancel := range cancels {
		cancel()
	}
}

// reverseList reports whether the exact reverse mapping exists for serial.
func (w *AdbWatcher) reverseList(ctx context.Context, serial, spec string) (bool, error) {
	out, err := w.adbCommand(ctx, "-s", serial, "reverse", "--list")
	if err != nil {
		return false, err
	}
	return parseAdbReverseList(out, spec), nil
}

// setupReverse ensures the reverse mapping exists. It returns whether the
// mapping is believed ready and whether this watcher created it.
func (w *AdbWatcher) setupReverse(ctx context.Context, serial string, tracker *connection.ConnectionStateTracker) (bool, bool) {
	tracker.Set(connection.StateConnecting)
	spec := fmt.Sprintf("tcp:%d", w.port)

	preExisting, listErr := w.reverseList(ctx, serial, spec)
	if listErr != nil {
		// The mapping may still exist; create is idempotent on most adb
		// versions, but ownership cannot be proven, so never remove it.
		logging.Debugf("adb watcher: reverse --list for [%s] failed: %v — creating mapping without ownership", serial, listErr)
	}
	if preExisting {
		tracker.Set(connection.StateConnected)
		logging.Logf("adb watcher: reverse tunnel already present for [%s] on port %d (not owned by relay)", serial, w.port)
		return true, false
	}

	args := []string{"-s", serial, "reverse", spec, spec}
	for attempt := 1; attempt <= adbMaxAttempts; attempt++ {
		out, err := w.adbCommand(ctx, args...)
		if err == nil {
			// Verification is diagnostic: a zero exit already means adb
			// accepted the rule. A failed verification does not invalidate a
			// mapping this watcher definitely created.
			if verified, verifyErr := w.reverseList(ctx, serial, spec); verifyErr != nil {
				logging.Debugf("adb watcher: reverse verification for [%s] failed: %v", serial, verifyErr)
			} else if !verified {
				logging.Debugf("adb watcher: reverse command succeeded but --list does not show %s for [%s]", spec, serial)
			}
			tracker.Set(connection.StateConnected)
			logging.Logf("adb watcher: reverse tunnel ready for [%s] on port %d", serial, w.port)
			return true, listErr == nil
		}

		if adbReverseAlreadyPresent(out) {
			tracker.Set(connection.StateConnected)
			logging.Logf("adb watcher: reverse tunnel already present for [%s] on port %d (not owned by relay)", serial, w.port)
			return true, false
		}

		backoff := adbBackoff(attempt)
		logging.Debugf("adb watcher: reverse attempt %d/%d for [%s] failed: %v — %s; retrying in %s",
			attempt, adbMaxAttempts, serial, err, strings.TrimSpace(string(out)), backoff)

		timer := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			timer.Stop()
			tracker.Set(connection.StateStopping)
			return false, false
		case <-timer.C:
		}
	}

	lastErr := fmt.Errorf("adb reverse failed after %d attempts", adbMaxAttempts)
	tracker.Fail(lastErr)
	logging.Errorf("adb watcher: [%s]: %v", serial, lastErr)
	return false, false
}

// cleanupOwnedReverse removes only mappings this watcher created. It uses a
// fresh context because the watcher context is already cancelled at shutdown.
func (w *AdbWatcher) cleanupOwnedReverse() {
	w.mu.Lock()
	serials := make([]string, 0, len(w.owned))
	for serial := range w.owned {
		serials = append(serials, serial)
	}
	w.owned = make(map[string]struct{})
	w.mu.Unlock()

	if len(serials) == 0 {
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), adbShutdownTimeout)
	defer cancel()
	spec := fmt.Sprintf("tcp:%d", w.port)

	for _, serial := range serials {
		if _, err := w.adbCommand(ctx, "-s", serial, "reverse", "--remove", spec); err != nil {
			logging.Warnf("adb watcher: could not remove owned reverse mapping %s for [%s]: %v", spec, serial, err)
			continue
		}
		logging.Logf("adb watcher: removed owned reverse mapping %s for [%s]", spec, serial)
	}
}
