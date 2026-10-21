package protocol

import (
	"bytes"
	"errors"
	"io"
	"strings"
	"testing"
)

// shortWriteRecorder returns a short count with a nil error on failCall. This
// models a transport such as USB bulk OUT that accepts fewer bytes than
// requested without reporting a syscall error.
type shortWriteRecorder struct {
	failCall int
	calls    int
	buf      bytes.Buffer
}

func (w *shortWriteRecorder) Write(p []byte) (int, error) {
	w.calls++
	n := len(p)
	if w.calls == w.failCall {
		n--
		if n < 0 {
			n = 0
		}
	}
	if n > 0 {
		_, _ = w.buf.Write(p[:n])
	}
	return n, nil
}

func TestWriteFrameShortHeader(t *testing.T) {
	w := &shortWriteRecorder{failCall: 1}

	err := WriteFrame(w, Frame{MsgType: MsgData, Payload: []byte("abc")})
	if !errors.Is(err, io.ErrShortWrite) {
		t.Fatalf("WriteFrame error = %v, want io.ErrShortWrite", err)
	}
	if !strings.Contains(err.Error(), "header") {
		t.Fatalf("error %q does not identify the header write", err)
	}
	if w.calls != 1 {
		t.Fatalf("writer calls = %d, want 1 (payload must not follow a short header)", w.calls)
	}
}

func TestWriteFrameShortPayload(t *testing.T) {
	w := &shortWriteRecorder{failCall: 2}

	err := WriteFrame(w, Frame{MsgType: MsgData, Payload: []byte("abc")})
	if !errors.Is(err, io.ErrShortWrite) {
		t.Fatalf("WriteFrame error = %v, want io.ErrShortWrite", err)
	}
	if !strings.Contains(err.Error(), "payload") {
		t.Fatalf("error %q does not identify the payload write", err)
	}
	if w.calls != 2 {
		t.Fatalf("writer calls = %d, want 2", w.calls)
	}
}
