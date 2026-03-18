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

func TestOversizedPayloadRejected(t *testing.T) {
	// Manually craft a frame header claiming a 128 KB payload
	var hdr [FrameHeaderSize]byte
	hdr[4] = 0x00
	hdr[5] = 0x02  // 0x00020000 = 131072 > MaxPayloadSize
	_, err := ReadFrame(bytes.NewReader(hdr[:]))
	if err == nil {
		t.Fatal("expected error for oversized payload, got nil")
	}
}