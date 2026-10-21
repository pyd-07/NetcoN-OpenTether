//go:build linux

package relay

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/sys/unix"
)

// session bridges one Android connection (TCP or AOA) to the shared TUN device.
//
// Data path (Android → internet):
//
//	Android conn → ReadFrame → Frame.Payload (raw IP pkt) → TUN.Write → kernel → internet
//
// Data path (internet → Android):
//
//	internet → kernel → TUN.Read → raw IP pkt → FrameWriter → Android conn
//
// Write strategy
// ──────────────
// Two FrameWriter implementations are available, selected by cfg.AOAMode:
//
//	TCP  (AOAMode=false): FlushWriter — batches small frames (DNS, QUIC ACKs)
//	                      under a 1 ms ticker. ~40% fewer syscalls on Pixel 6/USB2.
//
//	AOA  (AOAMode=true):  DirectWriter — sends each frame as one Write call =
//	                      one USB bulk OUT transfer. Prevents the frame corruption
//	                      that occurs when Android's USB accessory driver returns
//	                      one USB transfer per read() and drops unread bytes from
//	                      a transfer that contained multiple batched frames.
type session struct {
	conn net.Conn
	tun  *TunDevice
	cfg  Config

	// fw is the frame writer for the internet→Android direction.
	// Initialised by tunToAndroid; accessed by sendPong via fwMu.
	fw   FrameWriter
	fwMu sync.Mutex

	// pongPending coalesces PING replies: at most one PONG write is in flight.
	// Without this, a slow or stalled write would accumulate one goroutine per
	// PING until the session ends.
	pongPending atomic.Bool
}

func newSession(conn net.Conn, tun *TunDevice, cfg Config) *session {
	return &session{conn: conn, tun: tun, cfg: cfg}
}

// run starts both bridge goroutines and blocks until either exits or ctx is cancelled.
func (s *session) run(ctx context.Context) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	// Closing conn unblocks any goroutine blocked on Read/Write.
	go func() {
		<-ctx.Done()
		s.conn.Close()
	}()

	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		defer cancel()
		s.androidToTun(ctx, cancel)
	}()

	go func() {
		defer wg.Done()
		defer cancel()
		s.tunToAndroid(ctx, cancel)
	}()

	wg.Wait()
	logf("session with %s ended", s.conn.RemoteAddr())
}

// androidToTun reads OTP frames from the Android connection and writes their
// raw IP payloads into the TUN device. cancel ends the whole session if a
// reply cannot be written completely.
func (s *session) androidToTun(ctx context.Context, cancel context.CancelFunc) {
	r := bufio.NewReaderSize(s.conn, s.cfg.MTU*4)

	for {
		if ctx.Err() != nil {
			return
		}

		frame, err := ReadFrame(r)
		if err != nil {
			if ctx.Err() == nil {
				debugf("androidToTun: read error: %v", err)
			}
			return
		}

		switch frame.MsgType {
		case MsgData:
			if len(frame.Payload) == 0 {
				continue
			}
			debugf("→ TUN  %d bytes  conn_id=%d", len(frame.Payload), frame.ConnID)
			n, err := s.tun.Write(frame.Payload)
			if err != nil {
				errorf("androidToTun: TUN write: %v", err)
				return
			}
			if n != len(frame.Payload) {
				errorf("androidToTun: TUN write: short write (%d of %d bytes)", n, len(frame.Payload))
				return
			}

		case MsgPing:
			// Reply asynchronously so a slow pong doesn't stall the read loop,
			// but coalesce replies so a stalled write cannot accumulate one
			// goroutine per PING. A partial frame corrupts the stream, so
			// cancel the session if the write fails.
			if s.pongPending.CompareAndSwap(false, true) {
				go func() {
					defer s.pongPending.Store(false)
					if err := s.sendPong(); err != nil {
						errorf("sendPong: %v", err)
						cancel()
					}
				}()
			} else {
				debugf("androidToTun: PING while a PONG is already pending; reply coalesced")
			}

		case MsgClose:
			debugf("androidToTun: CLOSE for conn_id=%d", frame.ConnID)

		case MsgError:
			errorf("client reported error (conn_id=%d): %s",
				frame.ConnID, string(frame.Payload))

		default:
			debugf("androidToTun: unknown message type 0x%02x (ignored)", frame.MsgType)
		}
	}
}

// tunToAndroid reads raw IP packets from the TUN device and sends them to
// Android wrapped in OTP frames.
//
// Write strategy:
//   - TCP mode:  FlushWriter — batches small frames under a 1 ms ticker.
//   - AOA mode:  DirectWriter — one Write per frame = one USB transfer.
//     Prevents the payload_length corruption caused by Android USB accessory
//     drivers that return one USB transfer per read() without buffering remainders.
//
// The unix.Poll loop with a 200 ms timeout keeps ctx cancellation responsive
// even when no packets are arriving (TUN fds don't use Go's netpoller).
// cancel is used to fail the session if a background flush discovers a short
// write that would otherwise leave a corrupted stream running.
func (s *session) tunToAndroid(ctx context.Context, cancel context.CancelFunc) {
	buf := make([]byte, s.cfg.MTU+4)
	fd := s.tun.Fd()

	// Select write strategy based on transport type.
	var fw FrameWriter
	if s.cfg.AOAMode {
		// AOA: one USB transfer per frame — no batching.
		fw = NewDirectWriter(s.conn)
	} else {
		// TCP: batch small frames under a 1 ms ticker. A failed background
		// flush means the stream may be truncated or desynchronised.
		fw = NewFlushWriter(ctx, s.conn, 32*1024, 512, 1*time.Millisecond, func(err error) {
			errorf("tunToAndroid: flush to Android: %v", err)
			cancel()
		})
	}
	defer func() {
		// Clear the shared pointer before closing so a late PING cannot send
		// into a writer owned by a finished session.
		s.fwMu.Lock()
		s.fw = nil
		s.fwMu.Unlock()
		fw.Close()
	}()

	// Store fw so sendPong can access it.
	s.fwMu.Lock()
	s.fw = fw
	s.fwMu.Unlock()

	pollFds := []unix.PollFd{{
		Fd:     int32(fd),
		Events: unix.POLLIN,
	}}

	for {
		if ctx.Err() != nil {
			return
		}

		n, err := unix.Poll(pollFds, 200)
		if err != nil {
			if err == unix.EINTR {
				continue
			}
			errorf("tunToAndroid: poll: %v", err)
			return
		}
		if ctx.Err() != nil {
			return
		}
		if n == 0 || pollFds[0].Revents&unix.POLLIN == 0 {
			continue
		}

		nr, err := unix.Read(fd, buf)
		if err != nil {
			if err == unix.EAGAIN || err == unix.EWOULDBLOCK {
				continue
			}
			if ctx.Err() == nil {
				errorf("tunToAndroid: TUN read: %v", err)
			}
			return
		}
		if nr == 0 {
			continue
		}

		debugf("← TUN  %d bytes → Android", nr)

		if err := fw.Send(Frame{
			ConnID:  0,
			MsgType: MsgData,
			Payload: buf[:nr],
		}); err != nil {
			if ctx.Err() == nil {
				errorf("tunToAndroid: write to Android: %v", err)
			}
			return
		}
	}
}

func (s *session) sendPong() error {
	s.fwMu.Lock()
	fw := s.fw
	s.fwMu.Unlock()

	if fw == nil {
		// tunToAndroid hasn't initialised fw yet. Dropping a PONG is safe: the
		// client will send another PING if it still needs liveness evidence.
		return nil
	}
	if err := fw.Send(Frame{MsgType: MsgPong}); err != nil {
		return fmt.Errorf("send pong: %w", err)
	}
	return nil
}
