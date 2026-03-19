package relay

import (
	"bufio"
	"context"
	"io"
	"sync"
	"time"
)

// FlushWriter wraps a bufio.Writer and flushes it to the underlying writer
// using two complementary strategies:
//
//  1. Immediate flush — any frame whose payload is ≥ largeThreshold bytes is
//     flushed right away. Large payloads (TCP bulk data, video streams) are
//     unlikely to be followed by more data within a few microseconds, so there
//     is no benefit to buffering them.
//
//  2. Ticker flush — a background goroutine flushes at most every `interval`.
//     This drains buffered small frames (DNS queries, QUIC ACKs, keep-alives)
//     with a bounded worst-case latency addition of `interval`.
//
// Typical parameters that work well for a VPN tunnel:
//
//	bufSize       = 32 * 1024   (32 KB internal buffer)
//	largeThreshold = 512        (bytes; above this → flush immediately)
//	interval      = 1ms         (max extra latency for small packets)
//
// FlushWriter is goroutine-safe. All public methods acquire the internal mutex.
type FlushWriter struct {
	mu            sync.Mutex
	bw            *bufio.Writer
	largeThreshold int
	cancel        context.CancelFunc
}

// NewFlushWriter creates a FlushWriter and starts the background flush ticker.
// The caller must call Close() when done to stop the ticker goroutine.
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
// If len(f.Payload) ≥ largeThreshold the buffer is flushed to the wire immediately.
// Otherwise the frame sits in the buffer until the ticker fires.
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

// Flush forces all buffered data to the underlying writer.
// Called by sendPong and by the ticker.
func (fw *FlushWriter) Flush() error {
	fw.mu.Lock()
	defer fw.mu.Unlock()
	return fw.bw.Flush()
}

// Close stops the background ticker goroutine.
// Does NOT close the underlying writer.
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
			// Ignore flush errors here — the session goroutine reading from
			// the same conn will detect the broken connection and exit cleanly.
			_ = fw.bw.Flush()
			fw.mu.Unlock()
		}
	}
}