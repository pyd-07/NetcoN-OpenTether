//go:build linux

package relay

import (
	"bufio"
	"context"
	"net"
	"sync"
	"golang.org/x/sys/unix"
)

// session bridges one Android TCP connection to the shared TUN device.
//
// Data path (Android → internet):
//   Android TCP conn → ReadFrame → Frame.Payload (raw IP pkt) → TUN.Write → kernel → internet
//
// Data path (internet → Android):
//   internet → kernel → TUN.Read → raw IP pkt → WriteFrame → Android TCP conn
type session struct {
	conn net.Conn
	tun  *TunDevice
	cfg  Config
	wmu  sync.Mutex // serialises writes to conn (tunToAndroid + sendPong share it)
}

func newSession(conn net.Conn, tun *TunDevice, cfg Config) *session {
	return &session{conn: conn, tun: tun, cfg: cfg}
}

// run starts both bridge goroutines and blocks until either exits or ctx is cancelled.
// When one goroutine exits (error or disconnect), it cancels the other via context.
func (s *session) run(ctx context.Context) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	// Ensure the TCP connection is closed when the session ends,
	// which unblocks the other goroutine if it's stuck on a read.
	go func() {
		<-ctx.Done()
		s.conn.Close()
	}()

	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		defer cancel() // exit of one goroutine triggers shutdown of the other
		s.androidToTun(ctx)
	}()

	go func() {
		defer wg.Done()
		defer cancel()
		s.tunToAndroid(ctx)
	}()

	wg.Wait()
	logf("session with %s ended", s.conn.RemoteAddr())
}

// androidToTun reads OTP frames from the TCP connection and writes their
// raw IP payloads into the TUN device.
func (s *session) androidToTun(ctx context.Context) {
	// Buffered reader reduces syscall overhead for small reads.
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
			if _, err := s.tun.Write(frame.Payload); err != nil {
				errorf("androidToTun: TUN write: %v", err)
				return
			}

		case MsgPing:
			go s.sendPong() // non-blocking: don't hold up the read loop

		case MsgClose:
			debugf("androidToTun: CLOSE for conn_id=%d", frame.ConnID)
			// With TUN approach, the kernel handles TCP teardown;
			// we just acknowledge and continue.

		case MsgError:
			errorf("client reported error (conn_id=%d): %s",
				frame.ConnID, string(frame.Payload))

		default:
			debugf("androidToTun: unknown message type 0x%02x (ignored)", frame.MsgType)
		}
	}
}

// tunToAndroid reads raw IP packets from the TUN device and sends them
// to the Android client wrapped in OTP frames.
//
// Uses syscall.Poll with a 200ms timeout to allow clean context cancellation,
// since os.File.Read on a TUN fd blocks indefinitely (TUN fds don't support
// Go's netpoller).
func (s *session) tunToAndroid(ctx context.Context) {
	buf := make([]byte, s.cfg.MTU+4) // +4 safety margin
	fd := s.tun.Fd()

	pollFds := []unix.PollFd{{
		Fd:     int32(fd),
		Events: unix.POLLIN,
	}}

	for {
		if ctx.Err() != nil {
			return
		}

		// Wait up to 200ms for a packet to arrive on the TUN fd.
		// This timeout is what makes ctx cancellation responsive.
		n, err := unix.Poll(pollFds, 200)
		if err != nil {
			if err == unix.EINTR {
				continue // interrupted by signal, retry
			}
			errorf("tunToAndroid: poll: %v", err)
			return
		}
		if ctx.Err() != nil {
			return
		}
		if n == 0 || pollFds[0].Revents&unix.POLLIN == 0 {
			continue // timeout or spurious wakeup, check ctx and retry
		}

		// Data is available — read exactly one IP packet.
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

		// Wrap the raw IP packet in an OTP frame and send to Android.
		// conn_id=0 because the TUN approach doesn't require per-connection
		// routing — the Android kernel reads the IP header and delivers to
		// the right socket automatically.
		s.wmu.Lock()
		err = WriteFrame(s.conn, Frame{
			ConnID:  0,
			MsgType: MsgData,
			Payload: buf[:nr],
		})
		s.wmu.Unlock()

		if err != nil {
			if ctx.Err() == nil {
				errorf("tunToAndroid: write to Android: %v", err)
			}
			return
		}
	}
}

func (s *session) sendPong() {
	s.wmu.Lock()
	defer s.wmu.Unlock()
	if err := WriteFrame(s.conn, Frame{MsgType: MsgPong}); err != nil {
		debugf("sendPong: %v", err)
	}
}