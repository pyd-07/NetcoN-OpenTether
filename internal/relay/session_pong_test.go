//go:build linux

package relay

import (
	"bytes"
	"context"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/pyd-07/opentether/internal/protocol"
)

// blockingFrameWriter blocks inside Send until released, modelling a stalled
// Android transport. It tracks how many PONG writes are in flight at once.
type blockingFrameWriter struct {
	started chan struct{}
	release chan struct{}

	mu            sync.Mutex
	sends         int
	concurrent    int
	maxConcurrent int
}

func newBlockingFrameWriter() *blockingFrameWriter {
	return &blockingFrameWriter{
		started: make(chan struct{}, 1),
		release: make(chan struct{}),
	}
}

func (w *blockingFrameWriter) Send(protocol.Frame) error {
	w.mu.Lock()
	w.sends++
	w.concurrent++
	if w.concurrent > w.maxConcurrent {
		w.maxConcurrent = w.concurrent
	}
	w.mu.Unlock()

	select {
	case w.started <- struct{}{}:
	default:
	}

	<-w.release

	w.mu.Lock()
	w.concurrent--
	w.mu.Unlock()
	return nil
}

func (w *blockingFrameWriter) Close() {}

func (w *blockingFrameWriter) stats() (sends, maxConcurrent int) {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.sends, w.maxConcurrent
}

func TestSessionPongRepliesAreCoalesced(t *testing.T) {
	conn, peer := net.Pipe()
	defer conn.Close()
	defer peer.Close()

	rec := newTunWriteRecorder(1)
	sess := NewSession(conn, rec.device(), testValidConfig())
	writer := newBlockingFrameWriter()
	sess.fw = writer

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := make(chan struct{})
	go func() {
		defer close(done)
		sess.androidToTun(ctx, cancel)
	}()

	ping := protocol.BuildFrame(protocol.Frame{MsgType: protocol.MsgPing})
	for i := 0; i < 50; i++ {
		if _, err := peer.Write(ping); err != nil {
			t.Fatalf("write PING %d: %v", i, err)
		}
	}

	select {
	case <-writer.started:
	case <-time.After(2 * time.Second):
		t.Fatal("no PONG write started")
	}

	// The first PONG is still blocked; all later PINGs must be coalesced
	// rather than spawning more goroutines/writes.
	time.Sleep(50 * time.Millisecond)
	if sends, _ := writer.stats(); sends != 1 {
		t.Fatalf("PONG sends while stalled = %d, want 1", sends)
	}

	close(writer.release)
	time.Sleep(50 * time.Millisecond)

	sends, maxConcurrent := writer.stats()
	if maxConcurrent > 1 {
		t.Fatalf("max concurrent PONG writes = %d, want at most 1", maxConcurrent)
	}
	if sends > 2 {
		t.Fatalf("PONG sends after release = %d, want at most 2", sends)
	}

	cancel()
	peer.Close()
	waitForSessionDone(t, done, "androidToTun PING coalescing")

	select {
	case extra := <-rec.writes:
		t.Fatalf("control frames leaked to TUN: %q", extra)
	default:
	}
}

func TestFlushWriterCloseFlushesBufferedFrames(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sink := &lockedBuffer{}
	fw := NewFlushWriter(ctx, sink, 1024, 512, time.Hour, nil)

	frame := protocol.Frame{ConnID: 7, MsgType: protocol.MsgData, Payload: []byte("buffered")}
	if err := fw.Send(frame); err != nil {
		t.Fatalf("Send: %v", err)
	}
	if sink.len() != 0 {
		t.Fatalf("frame was not buffered before Close: %q", sink.bytes())
	}

	fw.Close()

	want := protocol.BuildFrame(frame)
	if got := sink.bytes(); !bytes.Equal(got, want) {
		t.Fatalf("Close wrote %x, want %x", got, want)
	}
}

type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) len() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Len()
}

func (b *lockedBuffer) bytes() []byte {
	b.mu.Lock()
	defer b.mu.Unlock()
	return append([]byte(nil), b.buf.Bytes()...)
}
