package protocol

import (
	"bytes"
	"strings"
	"testing"
)

// These are literal wire bytes. They must not be produced by calling the
// encoder under test; otherwise a shared offset/endianness bug would pass.
var (
	goldenDataFrame = []byte{
		0x01, 0x02, 0x03, 0x04, // conn_id 0x01020304
		0x00, 0x00, 0x00, 0x03, // payload_length 3
		0x01,       // MsgData
		0x02,       // FlagSYN
		0x00, 0x00, // reserved
		'A', 'B', 'C', // payload
	}
	goldenZeroPong = []byte{
		0x00, 0x00, 0x00, 0x00, // conn_id 0
		0x00, 0x00, 0x00, 0x00, // payload_length 0
		0x06,       // MsgPong
		0x00,       // flags
		0x00, 0x00, // reserved
	}
)

func TestBuildFrameGoldenData(t *testing.T) {
	got := BuildFrame(Frame{
		ConnID:  0x01020304,
		MsgType: MsgData,
		Flags:   FlagSYN,
		Payload: []byte("ABC"),
	})
	if !bytes.Equal(got, goldenDataFrame) {
		t.Fatalf("BuildFrame bytes mismatch\n got: % x\nwant: % x", got, goldenDataFrame)
	}
}

func TestWriteFrameGoldenData(t *testing.T) {
	var buf bytes.Buffer
	err := WriteFrame(&buf, Frame{
		ConnID:  0x01020304,
		MsgType: MsgData,
		Flags:   FlagSYN,
		Payload: []byte("ABC"),
	})
	if err != nil {
		t.Fatalf("WriteFrame: %v", err)
	}
	if !bytes.Equal(buf.Bytes(), goldenDataFrame) {
		t.Fatalf("WriteFrame bytes mismatch\n got: % x\nwant: % x", buf.Bytes(), goldenDataFrame)
	}
}

func TestBuildFrameGoldenZeroPong(t *testing.T) {
	got := BuildFrame(Frame{MsgType: MsgPong})
	if !bytes.Equal(got, goldenZeroPong) {
		t.Fatalf("zero-payload PONG mismatch\n got: % x\nwant: % x", got, goldenZeroPong)
	}
}

func TestBuildFrameMaxPayload(t *testing.T) {
	payload := bytes.Repeat([]byte{0xAB}, MaxPayloadSize)
	got := BuildFrame(Frame{ConnID: 1, MsgType: MsgData, Payload: payload})

	if len(got) != FrameHeaderSize+MaxPayloadSize {
		t.Fatalf("length = %d, want %d", len(got), FrameHeaderSize+MaxPayloadSize)
	}
	wantHeader := []byte{0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0xFF, 0xFF, 0x01, 0x00, 0x00, 0x00}
	if !bytes.Equal(got[:FrameHeaderSize], wantHeader) {
		t.Fatalf("max-payload header = % x, want % x", got[:FrameHeaderSize], wantHeader)
	}
	if got[FrameHeaderSize] != 0xAB || got[len(got)-1] != 0xAB {
		t.Fatal("max payload was not preserved at the boundaries")
	}

	decoded, err := ReadFrame(bytes.NewReader(got))
	if err != nil {
		t.Fatalf("ReadFrame(max payload): %v", err)
	}
	if len(decoded.Payload) != MaxPayloadSize {
		t.Fatalf("decoded payload length = %d, want %d", len(decoded.Payload), MaxPayloadSize)
	}
}

func TestReadFrameLengthBoundaries(t *testing.T) {
	tests := []struct {
		name    string
		length  []byte
		wantErr string
	}{
		{
			name:    "max supported",
			length:  []byte{0x00, 0x00, 0xFF, 0xFF},
			wantErr: "",
		},
		{
			name:    "first unsupported",
			length:  []byte{0x00, 0x01, 0x00, 0x00},
			wantErr: "65536",
		},
		{
			name:    "all ones",
			length:  []byte{0xFF, 0xFF, 0xFF, 0xFF},
			wantErr: "oversized",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			hdr := make([]byte, FrameHeaderSize)
			copy(hdr[4:8], tc.length)
			hdr[8] = MsgData

			if tc.wantErr == "" {
				frame := append(hdr, make([]byte, MaxPayloadSize)...)
				decoded, err := ReadFrame(bytes.NewReader(frame))
				if err != nil {
					t.Fatalf("expected success, got %v", err)
				}
				if len(decoded.Payload) != MaxPayloadSize {
					t.Fatalf("payload length = %d, want %d", len(decoded.Payload), MaxPayloadSize)
				}
				return
			}

			_, err := ReadFrame(bytes.NewReader(hdr))
			if err == nil {
				t.Fatal("expected an error, got nil")
			}
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("error %q does not contain %q", err, tc.wantErr)
			}
		})
	}
}

func TestReadFrameTruncated(t *testing.T) {
	t.Run("short header", func(t *testing.T) {
		_, err := ReadFrame(bytes.NewReader(make([]byte, FrameHeaderSize-1)))
		if err == nil || !strings.Contains(err.Error(), "read header") {
			t.Fatalf("error = %v, want read header error", err)
		}
	})

	t.Run("short payload", func(t *testing.T) {
		// Header claims 5 payload bytes, only 3 are supplied.
		frame := []byte{
			0x00, 0x00, 0x00, 0x01,
			0x00, 0x00, 0x00, 0x05,
			0x01, 0x00, 0x00, 0x00,
			0x01, 0x02, 0x03,
		}
		_, err := ReadFrame(bytes.NewReader(frame))
		if err == nil || !strings.Contains(err.Error(), "read payload") {
			t.Fatalf("error = %v, want read payload error", err)
		}
	})
}

func TestReadFrameReservedAndUnknownValues(t *testing.T) {
	t.Run("reserved bytes ignored", func(t *testing.T) {
		frame := []byte{
			0x00, 0x00, 0x00, 0x01,
			0x00, 0x00, 0x00, 0x02,
			0x01, 0x00, 0xDE, 0xAD,
			'A', 'B',
		}
		decoded, err := ReadFrame(bytes.NewReader(frame))
		if err != nil {
			t.Fatalf("ReadFrame: %v", err)
		}
		if !bytes.Equal(decoded.Payload, []byte("AB")) {
			t.Fatalf("payload = %q, want AB", decoded.Payload)
		}
	})

	t.Run("unknown type and flags preserved", func(t *testing.T) {
		frame := []byte{
			0x00, 0x00, 0x00, 0x02,
			0x00, 0x00, 0x00, 0x01,
			0x7F, 0xFF, 0x00, 0x00,
			0x42,
		}
		decoded, err := ReadFrame(bytes.NewReader(frame))
		if err != nil {
			t.Fatalf("ReadFrame: %v", err)
		}
		if decoded.MsgType != 0x7F || decoded.Flags != 0xFF {
			t.Fatalf("type/flags = 0x%02x/0x%02x, want 0x7f/0xff", decoded.MsgType, decoded.Flags)
		}
	})
}
