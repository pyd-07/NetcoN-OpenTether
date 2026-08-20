package relay

import (
	"fmt"
	"sync"
)

// ConnectionState describes the lifecycle of a transport connection.
type ConnectionState uint8

const (
	StateDisconnected ConnectionState = iota
	StateDetecting
	StateConnecting
	StateConnected
	StateStopping
	StateFailed
)

func (s ConnectionState) String() string {
	switch s {
	case StateDisconnected:
		return "disconnected"
	case StateDetecting:
		return "detecting"
	case StateConnecting:
		return "connecting"
	case StateConnected:
		return "connected"
	case StateStopping:
		return "stopping"
	case StateFailed:
		return "failed"
	default:
		return "unknown"
	}
}

// ConnectionStateTracker serializes transport state transitions and keeps the
// latest failure reason available for diagnostics.
type ConnectionStateTracker struct {
	mu       sync.RWMutex
	state    ConnectionState
	lastErr  error
}

func NewConnectionStateTracker() *ConnectionStateTracker {
	return &ConnectionStateTracker{state: StateDisconnected}
}

func (t *ConnectionStateTracker) Set(state ConnectionState) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.state = state
	if state != StateFailed {
		t.lastErr = nil
	}
}

func (t *ConnectionStateTracker) Fail(err error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.state = StateFailed
	t.lastErr = err
}

func (t *ConnectionStateTracker) Snapshot() (ConnectionState, error) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.state, t.lastErr
}

func (t *ConnectionStateTracker) String() string {
	state, err := t.Snapshot()
	if err == nil {
		return state.String()
	}
	return fmt.Sprintf("%s: %v", state, err)
}
