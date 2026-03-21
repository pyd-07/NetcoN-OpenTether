package relay

import (
	"bytes"
	"testing"
)

func TestRoundtrip(t *testing.T) {
	cases := []Frame{
		{ConnID: 1, MsgType: MsgData, Flags: 0, Payload: []byte("hello")},
		{ConnID: 0xDEADBEEF, MsgType: MsgPing, Flags: FlagSYN},
		{ConnID: 42, MsgType: MsgClose, Flags: FlagFIN, Payload: nil},
	}

	for _, want := range cases {
		var buf bytes.Buffer
		if err := WriteFrame(&buf, want); err != nil {
			t.Fatalf("WriteFrame: %v", err)
		}
		got, err := ReadFrame(&buf)
		if err != nil {
			t.Fatalf("ReadFrame: %v", err)
		}
		if got.ConnID != want.ConnID || got.MsgType != want.MsgType ||
			got.Flags != want.Flags || !bytes.Equal(got.Payload, want.Payload) {
			t.Errorf("roundtrip mismatch:\n  want %+v\n  got  %+v", want, got)
		}
	}
}

func TestBuildFrameRoundtrip(t *testing.T) {
	cases := []Frame{
		{ConnID: 1, MsgType: MsgData, Flags: 0, Payload: []byte("hello world")},
		{ConnID: 0xDEADBEEF, MsgType: MsgPing, Flags: FlagSYN},
		{ConnID: 0, MsgType: MsgPong, Flags: 0, Payload: nil},
		{ConnID: 99, MsgType: MsgData, Flags: 0, Payload: make([]byte, 1400)},
	}

	for _, want := range cases {
		buf := BuildFrame(want)

		// Verify total size
		if len(buf) != FrameHeaderSize+len(want.Payload) {
			t.Errorf("BuildFrame: expected %d bytes, got %d", FrameHeaderSize+len(want.Payload), len(buf))
		}

		// Verify it round-trips through ReadFrame
		got, err := ReadFrame(bytes.NewReader(buf))
		if err != nil {
			t.Fatalf("ReadFrame(BuildFrame(...)): %v", err)
		}
		if got.ConnID != want.ConnID || got.MsgType != want.MsgType ||
			got.Flags != want.Flags || !bytes.Equal(got.Payload, want.Payload) {
			t.Errorf("BuildFrame roundtrip mismatch:\n  want %+v\n  got  %+v", want, got)
		}
	}
}

// TestBuildFrameMatchesWriteFrame verifies that BuildFrame and WriteFrame
// produce identical bytes for the same input.
func TestBuildFrameMatchesWriteFrame(t *testing.T) {
	f := Frame{ConnID: 0x12345678, MsgType: MsgData, Flags: FlagSYN, Payload: []byte("test payload")}

	var wfBuf bytes.Buffer
	if err := WriteFrame(&wfBuf, f); err != nil {
		t.Fatalf("WriteFrame: %v", err)
	}

	bfBuf := BuildFrame(f)

	if !bytes.Equal(wfBuf.Bytes(), bfBuf) {
		t.Errorf("BuildFrame and WriteFrame produced different bytes:\n  WriteFrame: %x\n  BuildFrame: %x",
			wfBuf.Bytes(), bfBuf)
	}
}

func TestOversizedPayloadRejected(t *testing.T) {
	// Manually craft a frame header claiming a 128 KB payload
	var hdr [FrameHeaderSize]byte
	hdr[4] = 0x00
	hdr[5] = 0x02 // 0x00020000 = 131072 > MaxPayloadSize
	_, err := ReadFrame(bytes.NewReader(hdr[:]))
	if err == nil {
		t.Fatal("expected error for oversized payload, got nil")
	}
}
