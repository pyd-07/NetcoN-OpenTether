package relay

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// recordingConn implements just enough of net.Conn for DirectWriter tests.
// The embedded nil net.Conn is never called.
type recordingConn struct {
	net.Conn
	n   int
	got []byte
}

func (c *recordingConn) Write(p []byte) (int, error) {
	c.got = append([]byte(nil), p...)
	return c.n, nil
}

func TestDirectWriterShortWriteFails(t *testing.T) {
	frame := Frame{ConnID: 1, MsgType: MsgData, Payload: []byte("abc")}
	wantLen := len(BuildFrame(frame))
	conn := &recordingConn{n: wantLen - 1}

	err := NewDirectWriter(conn).Send(frame)
	if !errors.Is(err, io.ErrShortWrite) {
		t.Fatalf("Send error = %v, want io.ErrShortWrite", err)
	}
	if len(conn.got) != wantLen {
		t.Fatalf("wrote %d bytes, want %d", len(conn.got), wantLen)
	}
}

func TestDirectWriterFullWriteSucceeds(t *testing.T) {
	frame := Frame{ConnID: 1, MsgType: MsgData, Payload: []byte("abc")}
	want := BuildFrame(frame)
	conn := &recordingConn{n: len(want)}

	if err := NewDirectWriter(conn).Send(frame); err != nil {
		t.Fatalf("Send: %v", err)
	}
	if len(conn.got) != len(want) {
		t.Fatalf("wrote %d bytes, want %d", len(conn.got), len(want))
	}
}

type shortFlushWriter struct{}

func (shortFlushWriter) Write(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	return len(p) - 1, nil
}

// A background flush can also discover a short write. The session must be told
// so it can cancel rather than keep using a corrupted frame stream.
func TestFlushWriterTickerReportsShortWrite(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	errCh := make(chan error, 1)
	fw := NewFlushWriter(ctx, shortFlushWriter{}, 1024, 512, time.Millisecond, func(err error) {
		select {
		case errCh <- err:
		default:
		}
		cancel()
	})
	defer fw.Close()

	if err := fw.Send(Frame{MsgType: MsgData, Payload: []byte("abc")}); err != nil {
		t.Fatalf("Send: %v", err)
	}

	select {
	case err := <-errCh:
		if !errors.Is(err, io.ErrShortWrite) {
			t.Fatalf("flush error = %v, want io.ErrShortWrite", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("ticker did not report the short write")
	}

	if ctx.Err() == nil {
		t.Fatal("flush error handler did not cancel the session context")
	}
}

// Small frames should stay buffered until an explicit flush or the ticker.
// Using a one-hour interval keeps the test deterministic while still starting
// the real ticker goroutine.
func TestFlushWriterBatchesSmallFramesUntilFlush(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var out bytes.Buffer
	fw := NewFlushWriter(ctx, &out, 4096, 512, time.Hour, nil)
	defer fw.Close()

	frame := Frame{ConnID: 7, MsgType: MsgData, Payload: []byte("abc")}
	if err := fw.Send(frame); err != nil {
		t.Fatalf("Send: %v", err)
	}
	if out.Len() != 0 {
		t.Fatalf("small frame flushed immediately: %d bytes", out.Len())
	}

	if err := fw.Flush(); err != nil {
		t.Fatalf("Flush: %v", err)
	}
	want := BuildFrame(frame)
	if !bytes.Equal(out.Bytes(), want) {
		t.Fatalf("flushed bytes = %x, want %x", out.Bytes(), want)
	}
}

// Frames at or above the large threshold must be written immediately.
func TestFlushWriterFlushesLargeFramesImmediately(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var out bytes.Buffer
	fw := NewFlushWriter(ctx, &out, 4096, 512, time.Hour, nil)
	defer fw.Close()

	frame := Frame{
		ConnID:  8,
		MsgType: MsgData,
		Payload: bytes.Repeat([]byte{0x41}, 512),
	}
	if err := fw.Send(frame); err != nil {
		t.Fatalf("Send: %v", err)
	}

	want := BuildFrame(frame)
	if !bytes.Equal(out.Bytes(), want) {
		t.Fatalf("large frame was not flushed immediately (got %d bytes, want %d)", out.Len(), len(want))
	}
}

// countingConn records how many Write calls and bytes a writer produces.
type countingConn struct {
	net.Conn
	mu     sync.Mutex
	writes int
	total  int
}

func (c *countingConn) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.writes++
	c.total += len(p)
	return len(p), nil
}

// AOA relies on exactly one Write per frame so each frame maps to one USB bulk
// transfer. This protects that invariant independently of the short-write test.
func TestDirectWriterWritesOneFramePerSend(t *testing.T) {
	conn := &countingConn{}
	fw := NewDirectWriter(conn)

	frames := []Frame{
		{ConnID: 1, MsgType: MsgData, Payload: []byte("one")},
		{ConnID: 2, MsgType: MsgData, Payload: []byte("two")},
	}
	wantBytes := 0
	for _, frame := range frames {
		if err := fw.Send(frame); err != nil {
			t.Fatalf("Send: %v", err)
		}
		wantBytes += len(BuildFrame(frame))
	}

	conn.mu.Lock()
	defer conn.mu.Unlock()
	if conn.writes != len(frames) {
		t.Fatalf("Write calls = %d, want %d", conn.writes, len(frames))
	}
	if conn.total != wantBytes {
		t.Fatalf("bytes written = %d, want %d", conn.total, wantBytes)
	}
}

// Once a flush fails, bufio is permanently failed and the stream is
// unrecoverable. The ticker must report once and stop, not call onError on
// every tick for the rest of the session.
func TestFlushWriterTickerStopsAfterFlushError(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var calls int32
	fw := NewFlushWriter(ctx, shortFlushWriter{}, 1024, 512, time.Millisecond, func(error) {
		atomic.AddInt32(&calls, 1)
	})
	defer fw.Close()

	if err := fw.Send(Frame{MsgType: MsgData, Payload: []byte("abc")}); err != nil {
		t.Fatalf("Send: %v", err)
	}

	deadline := time.Now().Add(2 * time.Second)
	for atomic.LoadInt32(&calls) == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if got := atomic.LoadInt32(&calls); got != 1 {
		t.Fatalf("onError calls after first failure = %d, want 1", got)
	}

	time.Sleep(50 * time.Millisecond)
	if got := atomic.LoadInt32(&calls); got != 1 {
		t.Fatalf("onError called %d times after the ticker should have stopped", got)
	}
}
