//go:build linux

package relay

import (
	"bytes"
	"context"
	"encoding/binary"
	"net"
	"os"
	"testing"
	"time"

	"github.com/pyd-07/opentether/internal/protocol"
)

// tunWriteRecorder captures packets injected into the TUN side without opening
// /dev/net/tun or requiring CAP_NET_ADMIN.
type tunWriteRecorder struct {
	writes chan []byte
}

func newTunWriteRecorder(capacity int) *tunWriteRecorder {
	return &tunWriteRecorder{writes: make(chan []byte, capacity)}
}

func (r *tunWriteRecorder) write(_ int, p []byte) (int, error) {
	r.writes <- append([]byte(nil), p...)
	return len(p), nil
}

func (r *tunWriteRecorder) device() *TunDevice {
	return &TunDevice{fd: -1, name: "test0", writeFn: r.write}
}

func waitForSessionDone(t *testing.T, done <-chan struct{}, what string) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
	}
}

// This exercises the Android -> TUN half of the session directly. It proves
// frame reassembly, empty DATA handling, control-frame filtering, and exact
// payload injection without a real TUN device or network access.
func TestSessionAndroidToTunDataAndControlFrames(t *testing.T) {
	conn, peer := net.Pipe()
	defer conn.Close()
	defer peer.Close()

	rec := newTunWriteRecorder(4)
	sess := NewSession(conn, rec.device(), testValidConfig())

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := make(chan struct{})
	go func() {
		defer close(done)
		sess.androidToTun(ctx, cancel)
	}()

	frames := []protocol.Frame{
		{ConnID: 1, MsgType: protocol.MsgData, Payload: []byte("first")},
		{ConnID: 1, MsgType: protocol.MsgData}, // zero-length DATA is ignored
		{ConnID: 1, MsgType: protocol.MsgClose},
		{ConnID: 2, MsgType: protocol.MsgError, Payload: []byte("client error")},
		{ConnID: 3, MsgType: 0x7f}, // unknown type is ignored
		{ConnID: 4, MsgType: protocol.MsgData, Payload: []byte("second")},
	}
	for _, frame := range frames {
		if _, err := peer.Write(protocol.BuildFrame(frame)); err != nil {
			t.Fatalf("write frame: %v", err)
		}
	}

	for _, want := range [][]byte{[]byte("first"), []byte("second")} {
		select {
		case got := <-rec.writes:
			if !bytes.Equal(got, want) {
				t.Fatalf("TUN write = %q, want %q", got, want)
			}
		case <-time.After(2 * time.Second):
			t.Fatalf("timed out waiting for TUN write %q", want)
		}
	}

	// Closing the peer makes the blocked protocol.ReadFrame return and lets the helper
	// finish. Any additional captured write would mean a control frame leaked
	// into the TUN data path.
	if err := peer.Close(); err != nil {
		t.Fatalf("close peer: %v", err)
	}
	waitForSessionDone(t, done, "androidToTun")

	select {
	case extra := <-rec.writes:
		t.Fatalf("unexpected extra TUN write: %q", extra)
	default:
	}
}

// A PING must produce exactly one PONG through the installed frame writer.
func TestSessionRepliesToPing(t *testing.T) {
	conn, peer := net.Pipe()
	defer conn.Close()
	defer peer.Close()

	rec := newTunWriteRecorder(1)
	sess := NewSession(conn, rec.device(), testValidConfig())
	sess.fw = NewDirectWriter(conn) // installed before the read loop starts

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := make(chan struct{})
	go func() {
		defer close(done)
		sess.androidToTun(ctx, cancel)
	}()

	if err := peer.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("set deadline: %v", err)
	}
	if _, err := peer.Write(protocol.BuildFrame(protocol.Frame{MsgType: protocol.MsgPing})); err != nil {
		t.Fatalf("write PING: %v", err)
	}

	reply, err := protocol.ReadFrame(peer)
	if err != nil {
		t.Fatalf("read PONG: %v", err)
	}
	if reply.MsgType != protocol.MsgPong {
		t.Fatalf("reply type = 0x%02x, want PONG 0x%02x", reply.MsgType, protocol.MsgPong)
	}

	cancel()
	peer.Close()
	waitForSessionDone(t, done, "androidToTun PING handling")
}

// Oversized frames must fail before any bytes reach the TUN data path.
func TestSessionAndroidToTunRejectsOversizedFrameBeforeTunWrite(t *testing.T) {
	conn, peer := net.Pipe()
	defer conn.Close()
	defer peer.Close()

	rec := newTunWriteRecorder(1)
	sess := NewSession(conn, rec.device(), testValidConfig())

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := make(chan struct{})
	go func() {
		defer close(done)
		sess.androidToTun(ctx, cancel)
	}()

	var hdr [protocol.FrameHeaderSize]byte
	binary.BigEndian.PutUint32(hdr[4:8], protocol.MaxPayloadSize+1)
	hdr[8] = protocol.MsgData
	if _, err := peer.Write(hdr[:]); err != nil {
		t.Fatalf("write oversized header: %v", err)
	}
	peer.Close()
	waitForSessionDone(t, done, "androidToTun oversized frame")

	select {
	case got := <-rec.writes:
		t.Fatalf("oversized frame reached TUN: %q", got)
	default:
	}
}

// This exercises the TUN -> Android half with a non-privileged pipe as the
// packet source. AOA mode selects DirectWriter, so the test also proves that a
// TUN packet becomes exactly one complete OTP frame.
func TestSessionTunToAndroidForwardsPacket(t *testing.T) {
	pr, pw, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe: %v", err)
	}
	defer pr.Close()
	defer pw.Close()

	conn, peer := net.Pipe()
	defer conn.Close()
	defer peer.Close()

	cfg := testValidConfig()
	cfg.AOAMode = true
	sess := NewSession(conn, &TunDevice{fd: int(pr.Fd()), name: "test0"}, cfg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := make(chan struct{})
	go func() {
		defer close(done)
		sess.tunToAndroid(ctx, cancel)
	}()

	packet := []byte{0x45, 0x00, 0x00, 0x14, 0xde, 0xad, 0xbe, 0xef}
	if _, err := pw.Write(packet); err != nil {
		t.Fatalf("write packet to TUN pipe: %v", err)
	}

	if err := peer.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("set deadline: %v", err)
	}
	frame, err := protocol.ReadFrame(peer)
	if err != nil {
		t.Fatalf("read forwarded frame: %v", err)
	}
	if frame.MsgType != protocol.MsgData {
		t.Fatalf("frame type = 0x%02x, want DATA 0x%02x", frame.MsgType, protocol.MsgData)
	}
	if !bytes.Equal(frame.Payload, packet) {
		t.Fatalf("forwarded payload = %x, want %x", frame.Payload, packet)
	}

	cancel()
	peer.Close()
	waitForSessionDone(t, done, "tunToAndroid")
}

// Cancelling the session context must close the Android connection and unblock
// both bridge directions.
func TestSessionRunStopsAndClosesConnOnCancel(t *testing.T) {
	pr, pw, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe: %v", err)
	}
	defer pr.Close()
	defer pw.Close()

	conn, peer := net.Pipe()
	defer conn.Close()
	defer peer.Close()

	sess := NewSession(conn, &TunDevice{fd: int(pr.Fd()), name: "test0"}, testValidConfig())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if err := peer.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatalf("set deadline: %v", err)
	}

	done := make(chan struct{})
	go func() {
		defer close(done)
		sess.Run(ctx)
	}()

	cancel()
	waitForSessionDone(t, done, "session.run cancellation")

	if _, err := peer.Read(make([]byte, 1)); err == nil {
		t.Fatal("peer connection remained open after session cancellation")
	}
}
