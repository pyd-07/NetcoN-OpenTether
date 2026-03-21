package relay

import (
	"bufio"
	"context"
	"io"
	"net"
	"sync"
	"time"
)

// FrameWriter is the common interface for sending OTP frames to Android.
// Both FlushWriter (TCP) and DirectWriter (AOA) implement this interface,
// allowing session.go to select the right strategy based on cfg.AOAMode.
type FrameWriter interface {
	Send(f Frame) error
	Close()
}

// ── DirectWriter ─────────────────────────────────────────────────────────────

// DirectWriter sends each OTP frame as a single atomic Write call with no
// internal buffering. It is the correct strategy for USB/AOA transport.
//
// Why batching breaks AOA
// ───────────────────────
// FlushWriter batches multiple frames into a 32 KB bufio buffer, then flushes
// them in a single AoaConn.Write call → single epOut.Write → single USB bulk
// OUT transfer. Android's /dev/usb_accessory kernel driver returns one USB
// transfer per read() syscall. DataInputStream.readFully reads N bytes by
// calling read() repeatedly, but on some Android devices and kernel versions the
// USB accessory driver does NOT carry forward unread bytes from a partial read —
// each read() dequeues exactly one USB transfer, discarding remaining bytes if
// the caller's buffer was smaller.
//
// The result: Android reads 12 bytes (Frame1 header) from Transfer1, then tries
// to read Frame1.payloadLen bytes. If Transfer1 contained Frame1+Frame2, Android
// might only get Frame1 from Transfer1, then Frame2's header arrives in Transfer2.
// DataInputStream.readFully then interprets Frame2's first 4 bytes as the
// continuation of Frame1's payload, producing a garbage payload_length value.
//
// DirectWriter avoids this by calling BuildFrame(f) to produce one allocation
// (header + payload) and writing it in a single Write call → single USB transfer.
// One frame in, one frame out, always aligned.
type DirectWriter struct {
	mu   sync.Mutex
	conn net.Conn
}

// NewDirectWriter creates a DirectWriter for the given connection.
func NewDirectWriter(conn net.Conn) *DirectWriter {
	return &DirectWriter{conn: conn}
}

// Send builds the complete OTP frame in a single allocation and writes it
// to the connection in one Write call. Goroutine-safe.
func (d *DirectWriter) Send(f Frame) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	_, err := d.conn.Write(BuildFrame(f))
	return err
}

// Close is a no-op for DirectWriter (no background goroutine to stop).
func (d *DirectWriter) Close() {}

// ── FlushWriter ───────────────────────────────────────────────────────────────

// FlushWriter wraps a bufio.Writer and flushes it using two strategies:
//
//  1. Immediate flush — frames whose payload is ≥ largeThreshold bytes are
//     flushed right away. Large frames (TCP bulk, video) benefit from low
//     latency more than batching.
//
//  2. Ticker flush — a background goroutine flushes at most every `interval`.
//     Small frames (DNS, QUIC ACKs, keep-alives) batch under the ticker with
//     a bounded worst-case latency of `interval`.
//
// Recommended parameters for TCP VPN tunnel:
//
//	bufSize        = 32 * 1024  (32 KB internal buffer)
//	largeThreshold = 512        (bytes; above this → flush immediately)
//	interval       = 1ms        (max extra latency for small packets)
//
// Do NOT use FlushWriter for AOA transport — use DirectWriter instead.
// FlushWriter is goroutine-safe; all public methods acquire the internal mutex.
type FlushWriter struct {
	mu             sync.Mutex
	bw             *bufio.Writer
	largeThreshold int
	cancel         context.CancelFunc
}

// NewFlushWriter creates a FlushWriter and starts the background ticker.
// Call Close() when done to stop the ticker goroutine.
func NewFlushWriter(
	ctx context.Context,
	w io.Writer,
	bufSize int,
	largeThreshold int,
	interval time.Duration,
) *FlushWriter {
	fctx, cancel := context.WithCancel(ctx)
	fw := &FlushWriter{
		bw:             bufio.NewWriterSize(w, bufSize),
		largeThreshold: largeThreshold,
		cancel:         cancel,
	}
	go fw.tickerLoop(fctx, interval)
	return fw
}

// Send serialises f into the internal buffer.
// Flushes immediately if len(f.Payload) ≥ largeThreshold, otherwise defers
// to the background ticker.
func (fw *FlushWriter) Send(f Frame) error {
	fw.mu.Lock()
	defer fw.mu.Unlock()

	if err := WriteFrame(fw.bw, f); err != nil {
		return err
	}
	if len(f.Payload) >= fw.largeThreshold {
		return fw.bw.Flush()
	}
	return nil
}

// Flush forces all buffered data to the underlying writer immediately.
func (fw *FlushWriter) Flush() error {
	fw.mu.Lock()
	defer fw.mu.Unlock()
	return fw.bw.Flush()
}

// Close stops the background ticker goroutine. Does NOT close the underlying writer.
func (fw *FlushWriter) Close() {
	fw.cancel()
}

func (fw *FlushWriter) tickerLoop(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			fw.mu.Lock()
			if err := fw.bw.Flush(); err != nil {
				// Log write failures so USB/TCP errors are visible in -v output.
				debugf("FlushWriter: flush error: %v", err)
			}
			fw.mu.Unlock()
		}
	}
}
