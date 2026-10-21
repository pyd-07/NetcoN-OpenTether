package relay

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeAdbRunner records every fully-built adb argument list and delegates the
// response to a test-supplied function. It never starts a real adb server.
type fakeAdbRunner struct {
	mu      sync.Mutex
	calls   [][]string
	respond func(args []string) ([]byte, error)
}

func (f *fakeAdbRunner) run(ctx context.Context, args []string) ([]byte, error) {
	f.mu.Lock()
	f.calls = append(f.calls, append([]string(nil), args...))
	f.mu.Unlock()
	if f.respond != nil {
		return f.respond(args)
	}
	return nil, nil
}

func (f *fakeAdbRunner) snapshot() [][]string {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([][]string, len(f.calls))
	for i := range f.calls {
		out[i] = append([]string(nil), f.calls[i]...)
	}
	return out
}

func (f *fakeAdbRunner) count(substr string) int {
	count := 0
	for _, call := range f.snapshot() {
		if strings.Contains(strings.Join(call, " "), substr) {
			count++
		}
	}
	return count
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

func TestParseAdbReverseList(t *testing.T) {
	tests := []struct {
		name string
		out  string
		spec string
		want bool
	}{
		{"bare mapping", "tcp:8765 tcp:8765\n", "tcp:8765", true},
		{"serial prefixed", "stub-device tcp:8765 tcp:8765\n", "tcp:8765", true},
		{"CRLF", "stub-device\ttcp:8765\ttcp:8765\r\n", "tcp:8765", true},
		{"different local port", "tcp:8766 tcp:8765\n", "tcp:8765", false},
		{"different remote port", "tcp:8765 tcp:8766\n", "tcp:8765", false},
		{"empty", "", "tcp:8765", false},
		{"header only", "List of reverse mappings\n", "tcp:8765", false},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := parseAdbReverseList([]byte(tc.out), tc.spec); got != tc.want {
				t.Fatalf("parseAdbReverseList(%q) = %v, want %v", tc.out, got, tc.want)
			}
		})
	}
}

func TestAdbSetupReverseCreatesAndOwnsAbsentMapping(t *testing.T) {
	runner := &fakeAdbRunner{respond: func(args []string) ([]byte, error) {
		if containsArg(args, "--list") {
			return nil, nil // absent
		}
		return nil, nil // create succeeds
	}}
	w := NewAdbWatcher(8765, "adb", 0)
	w.run = runner.run

	tracker := NewConnectionStateTracker()
	established, owned := w.setupReverse(context.Background(), "stub-device", tracker)

	if !established {
		t.Fatal("setupReverse did not establish the mapping")
	}
	if !owned {
		t.Fatal("mapping created from an absent state must be owned")
	}
	if state, _ := tracker.Snapshot(); state != StateConnected {
		t.Fatalf("tracker state = %v, want connected", state)
	}
	if got := runner.count("-s stub-device reverse tcp:8765 tcp:8765"); got != 1 {
		t.Fatalf("create count = %d, want 1 (calls: %v)", got, runner.snapshot())
	}
}

func TestAdbSetupReverseDoesNotOwnPreexistingMapping(t *testing.T) {
	runner := &fakeAdbRunner{respond: func(args []string) ([]byte, error) {
		if containsArg(args, "--list") {
			return []byte("stub-device tcp:8765 tcp:8765\n"), nil
		}
		return nil, nil
	}}
	w := NewAdbWatcher(8765, "adb", 0)
	w.run = runner.run

	tracker := NewConnectionStateTracker()
	established, owned := w.setupReverse(context.Background(), "stub-device", tracker)

	if !established {
		t.Fatal("pre-existing mapping must be reported as established")
	}
	if owned {
		t.Fatal("pre-existing mapping must not be owned")
	}
	if got := runner.count("reverse tcp:8765 tcp:8765"); got != 0 {
		t.Fatalf("create count = %d, want 0 for a pre-existing mapping (calls: %v)", got, runner.snapshot())
	}
}

func TestAdbSetupReverseListFailureWithholdsOwnership(t *testing.T) {
	runner := &fakeAdbRunner{respond: func(args []string) ([]byte, error) {
		if containsArg(args, "--list") {
			return []byte("error: device offline"), errors.New("adb list failed")
		}
		return nil, nil
	}}
	w := NewAdbWatcher(8765, "adb", 0)
	w.run = runner.run

	tracker := NewConnectionStateTracker()
	established, owned := w.setupReverse(context.Background(), "stub-device", tracker)

	if !established {
		t.Fatal("create success should still establish the mapping")
	}
	if owned {
		t.Fatal("ownership must be withheld when absence could not be proven")
	}
}

func TestAdbSetupReverseAlreadyPresentErrorIsEstablished(t *testing.T) {
	runner := &fakeAdbRunner{respond: func(args []string) ([]byte, error) {
		if containsArg(args, "--list") {
			return []byte("error: closed"), errors.New("adb list failed")
		}
		return []byte("adb: error: cannot rebind listener: Address already in use"), errors.New("exit status 1")
	}}
	w := NewAdbWatcher(8765, "adb", 0)
	w.run = runner.run

	tracker := NewConnectionStateTracker()
	established, owned := w.setupReverse(context.Background(), "stub-device", tracker)

	if !established {
		t.Fatal("an already-present mapping error must be treated as usable")
	}
	if owned {
		t.Fatal("an unverified pre-existing mapping must not be owned")
	}
}

func TestAdbCleanupRemovesOnlyOwnedMappings(t *testing.T) {
	runner := &fakeAdbRunner{}
	w := NewAdbWatcher(8765, "adb", 0)
	w.run = runner.run
	w.owned["owned-device"] = struct{}{}

	w.cleanupOwnedReverse()

	if got := runner.count("-s owned-device reverse --remove tcp:8765"); got != 1 {
		t.Fatalf("remove count = %d, want 1 (calls: %v)", got, runner.snapshot())
	}
	if len(w.owned) != 0 {
		t.Fatalf("owned map not cleared: %v", w.owned)
	}

	// A second cleanup must not issue a duplicate remove.
	w.cleanupOwnedReverse()
	if got := runner.count("reverse --remove"); got != 1 {
		t.Fatalf("second cleanup issued %d removes, want 1 total", got)
	}
}

func TestAdbWatchRemovesOwnedMappingOnCancel(t *testing.T) {
	runner := &fakeAdbRunner{respond: func(args []string) ([]byte, error) {
		if containsArg(args, "devices") {
			return []byte("List of devices attached\nstub-device\tdevice\n"), nil
		}
		if containsArg(args, "--list") {
			return nil, nil
		}
		return nil, nil
	}}
	w := NewAdbWatcher(8765, "adb", 0)
	w.run = runner.run

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		w.Watch(ctx)
		close(done)
	}()

	waitForCondition(t, "reverse mapping ownership", func() bool {
		w.mu.Lock()
		defer w.mu.Unlock()
		_, ok := w.owned["stub-device"]
		return ok
	})

	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("watcher did not finish after cancellation")
	}

	if got := runner.count("-s stub-device reverse --remove tcp:8765"); got != 1 {
		t.Fatalf("shutdown remove count = %d, want 1 (calls: %v)", got, runner.snapshot())
	}
}

func TestAdbPollDoesNotStartDuplicateSetup(t *testing.T) {
	release := make(chan struct{})
	runner := &fakeAdbRunner{respond: func(args []string) ([]byte, error) {
		if containsArg(args, "devices") {
			return []byte("List of devices attached\nstub-device\tdevice\n"), nil
		}
		if containsArg(args, "--list") {
			return nil, nil
		}
		<-release // hold the create call so the first setup stays in flight
		return nil, nil
	}}
	w := NewAdbWatcher(8765, "adb", 0)
	w.run = runner.run

	w.poll(context.Background())
	waitForCondition(t, "first reverse create", func() bool {
		return runner.count("reverse tcp:8765 tcp:8765") >= 1
	})

	w.poll(context.Background())
	w.poll(context.Background())

	close(release)
	waitForCondition(t, "setup to finish", func() bool {
		w.mu.Lock()
		defer w.mu.Unlock()
		_, established := w.established["stub-device"]
		return established
	})

	if got := runner.count("-s stub-device reverse tcp:8765 tcp:8765"); got != 1 {
		t.Fatalf("reverse create count = %d, want 1 (calls: %v)", got, runner.snapshot())
	}
}

func TestAdbCommandHonorsContextDeadline(t *testing.T) {
	w := NewAdbWatcher(8765, "adb", 0)
	w.commandTimeout = 50 * time.Millisecond
	w.run = func(ctx context.Context, args []string) ([]byte, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	}

	start := time.Now()
	_, err := w.adbCommand(context.Background(), "devices")
	if err == nil {
		t.Fatal("expected a timeout error")
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("error = %v, want context.DeadlineExceeded", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("command took %s, want a bounded timeout", elapsed)
	}
}

func TestAdbBackoffRetryAfterFailureDoesNotHotLoop(t *testing.T) {
	var attempts int
	runner := &fakeAdbRunner{respond: func(args []string) ([]byte, error) {
		if containsArg(args, "devices") {
			return []byte("List of devices attached\nstub-device\tdevice\n"), nil
		}
		if containsArg(args, "--list") {
			return nil, nil
		}
		attempts++
		return nil, fmt.Errorf("reverse failed")
	}}
	w := NewAdbWatcher(8765, "adb", 0)
	w.run = runner.run

	// Direct setup exhausts its bounded attempts and reports failure.
	tracker := NewConnectionStateTracker()
	established, owned := w.setupReverse(context.Background(), "stub-device", tracker)
	if established || owned {
		t.Fatalf("failed setup returned established=%v owned=%v, want false/false", established, owned)
	}
	if attempts != adbMaxAttempts {
		t.Fatalf("attempts = %d, want %d", attempts, adbMaxAttempts)
	}
}

func containsArg(args []string, want string) bool {
	for _, arg := range args {
		if arg == want {
			return true
		}
	}
	return false
}
