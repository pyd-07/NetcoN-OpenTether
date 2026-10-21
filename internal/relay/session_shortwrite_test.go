//go:build linux

package relay

import (
	"context"
	"io"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pyd-07/opentether/internal/protocol"
)

// pongFailureConn delivers one PING frame, then blocks until Close. Every write
// reports a short count with a nil error, modelling a partial USB bulk write.
type pongFailureConn struct {
	readGate  <-chan struct{}
	closed    chan struct{}
	closeOnce sync.Once
	mu        sync.Mutex
	sentPing  bool
	writes    atomic.Int32
}

func (c *pongFailureConn) Read(p []byte) (int, error) {
	<-c.readGate

	c.mu.Lock()
	if !c.sentPing {
		c.sentPing = true
		c.mu.Unlock()
		ping := []byte{
			0x00, 0x00, 0x00, 0x00,
			0x00, 0x00, 0x00, 0x00,
			protocol.MsgPing, 0x00, 0x00, 0x00,
		}
		return copy(p, ping), nil
	}
	c.mu.Unlock()

	<-c.closed
	return 0, io.EOF
}

func (c *pongFailureConn) Write(p []byte) (int, error) {
	c.writes.Add(1)
	if len(p) == 0 {
		return 0, nil
	}
	return len(p) - 1, nil
}

func (c *pongFailureConn) Close() error {
	c.closeOnce.Do(func() { close(c.closed) })
	return nil
}

func (c *pongFailureConn) LocalAddr() net.Addr              { return testAddr("local") }
func (c *pongFailureConn) RemoteAddr() net.Addr             { return testAddr("remote") }
func (c *pongFailureConn) SetDeadline(time.Time) error      { return nil }
func (c *pongFailureConn) SetReadDeadline(time.Time) error  { return nil }
func (c *pongFailureConn) SetWriteDeadline(time.Time) error { return nil }

type testAddr string

func (a testAddr) Network() string { return "test" }
func (a testAddr) String() string  { return string(a) }

// A short PONG write desynchronises the OTP stream. The session must cancel
// itself and close the connection instead of continuing to run.
func TestSessionCancelsOnShortPongWrite(t *testing.T) {
	pr, pw, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe: %v", err)
	}
	defer pr.Close()
	defer pw.Close()

	gate := make(chan struct{})
	conn := &pongFailureConn{
		readGate: gate,
		closed:   make(chan struct{}),
	}
	tun := &TunDevice{fd: int(pr.Fd()), name: "test0"}

	cfg := testValidConfig()
	cfg.AOAMode = true
	sess := NewSession(conn, tun, cfg)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	done := make(chan struct{})
	go func() {
		sess.Run(ctx)
		close(done)
	}()

	// Wait until tunToAndroid has installed the DirectWriter. Otherwise the
	// PONG could be dropped because sendPong still sees a nil writer.
	deadline := time.Now().Add(2 * time.Second)
	for {
		sess.fwMu.Lock()
		ready := sess.fw != nil
		sess.fwMu.Unlock()
		if ready {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("session writer was not initialised")
		}
		time.Sleep(time.Millisecond)
	}

	close(gate) // deliver the PING frame

	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("session did not terminate after a short PONG write")
	}

	if got := conn.writes.Load(); got == 0 {
		t.Fatal("PONG write was not attempted")
	}
	select {
	case <-conn.closed:
	default:
		t.Fatal("connection was not closed after a short PONG write")
	}
}
