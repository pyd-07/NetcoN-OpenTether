package relay

import (
	"context"
	"errors"
	"io"
	"net"
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
