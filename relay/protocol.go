package relay

import (
	"fmt"
	"io"
	"encoding/binary"
)

// Wire format (12-byte header, big-endian):
//
//   0       4       8  9  10      12
//   |conn_id|pay_len|ty|fl|reservd|...payload...
//
// conn_id     uint32  Logical connection identifier (assigned by Android)
// pay_len     uint32  Payload length in bytes (0 is valid)
// ty          uint8   Message type (see Msg* constants)
// fl          uint8   Flags bitmask (see Flag* constants)
// reserved    uint16  Must be zero; reserved for future use

const (
	FrameHeaderSize = 12
	MaxPayloadSize = 65535

	// Message Types
	MsgData uint8 = 0x01 // raw IP packet, bidirectional
	MsgConnect uint8 = 0x02 // Android notifies realy of new connection
	MsgClose uint8 = 0x03 // graceful teardown
	MsgError uint8 = 0x04 // error report; payload is a UTF-8 string
	MsgPing uint8 = 0x05 // keepalive probe
	MsgPong uint8 = 0x06 // keepalive relay
	

	// Flag bits
	FlagFIN uint8 = 0x01 // last packet for this conn_id
	FlagSYN uint8 = 0x02 // first packet (used with MsgConnect)
	FlagRST uint8 = 0x04 // reset; abort connection immediately
)

// Frame is a decoded OTP protocol message
type Frame struct {
	ConnID uint32
	MsgType uint8
	Flags uint8
	Payload []byte // nil when pay_len=0
}

// ReadFrame reads exactly one frame from r (blocking until complete).
// Returns an error if the connection closes or a malformed frame arrives.
func ReadFrame(r io.Reader) (Frame, error) {
	var hdr [FrameHeaderSize]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return Frame{}, fmt.Errorf("read header: %w", err)
	}

	connID  := binary.BigEndian.Uint32(hdr[0:4])
	payLen  := binary.BigEndian.Uint32(hdr[4:8])
	msgType := hdr[8]
	flags   := hdr[9]
	// hdr[10:12] reserved — ignored on read

	if payLen > MaxPayloadSize {
		return Frame{}, fmt.Errorf("oversized payload: %d bytes (limit %d)", payLen, MaxPayloadSize)
	}

	var payload []byte
	if payLen > 0 {
		payload = make([]byte, payLen)
		if _, err := io.ReadFull(r, payload); err != nil {
			return Frame{}, fmt.Errorf("read payload (%d bytes): %w", payLen, err)
		}
	}

	return Frame{
		ConnID:  connID,
		MsgType: msgType,
		Flags:   flags,
		Payload: payload,
	}, nil
}

// WriteFrame writes one frame to w. NOT goroutine-safe; callers must
// hold a mutex when multiple goroutines share the same writer.
func WriteFrame(w io.Writer, f Frame) error {
	var hdr [FrameHeaderSize]byte
	binary.BigEndian.PutUint32(hdr[0:4], f.ConnID)
	binary.BigEndian.PutUint32(hdr[4:8], uint32(len(f.Payload)))
	hdr[8] = f.MsgType
	hdr[9] = f.Flags
	// hdr[10:12] = 0 already (zero value)

	if _, err := w.Write(hdr[:]); err != nil {
		return fmt.Errorf("write header: %w", err)
	}
	if len(f.Payload) > 0 {
		if _, err := w.Write(f.Payload); err != nil {
			return fmt.Errorf("write payload: %w", err)
		}
	}
	return nil
}