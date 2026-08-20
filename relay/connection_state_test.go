package relay

import (
	"errors"
	"testing"
)

func TestConnectionStateTracker(t *testing.T) {
	tracker := NewConnectionStateTracker()
	state, err := tracker.Snapshot()
	if state != StateDisconnected || err != nil {
		t.Fatalf("initial state = %s, %v; want disconnected, nil", state, err)
	}

	tracker.Set(StateConnecting)
	state, err = tracker.Snapshot()
	if state != StateConnecting || err != nil {
		t.Fatalf("connecting state = %s, %v; want connecting, nil", state, err)
	}

	wantErr := errors.New("transport unavailable")
	tracker.Fail(wantErr)
	state, err = tracker.Snapshot()
	if state != StateFailed || !errors.Is(err, wantErr) {
		t.Fatalf("failed state = %s, %v; want failed, transport unavailable", state, err)
	}

	tracker.Set(StateConnected)
	state, err = tracker.Snapshot()
	if state != StateConnected || err != nil {
		t.Fatalf("connected state = %s, %v; want connected, nil", state, err)
	}
}

func TestConnectionStateString(t *testing.T) {
	tests := map[ConnectionState]string{
		StateDisconnected: "disconnected",
		StateDetecting:    "detecting",
		StateConnecting:   "connecting",
		StateConnected:    "connected",
		StateStopping:     "stopping",
		StateFailed:       "failed",
	}
	for state, want := range tests {
		if got := state.String(); got != want {
			t.Errorf("state %d string = %q; want %q", state, got, want)
		}
	}
}
