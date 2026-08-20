//go:build linux && aoa

// Build with: go build -tags aoa ./...
// Requires:   apt install libusb-1.0-0-dev
//             go get github.com/google/gousb

package relay

import (
	"context"
	"fmt"
	"io"
	"net"
	"sync"
	"time"

	"github.com/google/gousb"
)

const (
	aoaGetProtocol uint8 = 51
	aoaSendString  uint8 = 52
	aoaStartMode   uint8 = 53

	aoaIdxManufacturer = 0
	aoaIdxModel        = 1
	aoaIdxDescription  = 2
	aoaIdxVersion      = 3
	aoaIdxURI          = 4
	aoaIdxSerial       = 5

	googleVID gousb.ID = 0x18D1
	aoaPID    gousb.ID = 0x2D00
	aoaADBPID gousb.ID = 0x2D01

	aoaReadBufSize = 16 * 1024
	aoaBackoffMin  = 500 * time.Millisecond
	aoaBackoffMax  = 8 * time.Second
	aoaMaxAttempts = 6
	aoaDetectWait  = 500 * time.Millisecond
)

var aoaStrings = [6]string{
	"OpenTether",
	"OpenTether Relay",
	"Reverse USB tethering — no root required",
	"1.0",
	"https://github.com/pyd-07/NetcoN-OpenTether",
	"opentether-relay-01",
}

var androidVIDs = []gousb.ID{
	0x04E8,
	0x18D1,
	0x2717,
	0x12D1,
	0x1BBB,
	0x0BB4,
	0x054C,
	0x2A70,
	0x22D9,
	0x19D2,
}

type AoaConn struct {
	dev        *gousb.Device
	intf       *gousb.Interface
	done       func()
	in         *gousb.InEndpoint
	out        *gousb.OutEndpoint
	pr         *io.PipeReader
	pw         *io.PipeWriter
	localAddr  net.Addr
	remoteAddr net.Addr
	closedOnce sync.Once
	closed     chan struct{}
}

func (c *AoaConn) readPump() {
	buf := make([]byte, aoaReadBufSize)
	for {
		n, err := c.in.Read(buf)
		if n > 0 {
			if _, werr := c.pw.Write(buf[:n]); werr != nil {
				return
			}
		}
		if err != nil {
			select {
			case <-c.closed:
				c.pw.CloseWithError(io.EOF)
			default:
				c.pw.CloseWithError(fmt.Errorf("USB IN read: %w", err))
			}
			return
		}
	}
}

func (c *AoaConn) Read(p []byte) (int, error) { return c.pr.Read(p) }

func (c *AoaConn) Write(p []byte) (int, error) {
	select {
	case <-c.closed:
		return 0, io.ErrClosedPipe
	default:
	}
	n, err := c.out.Write(p)
	if err != nil {
		return n, fmt.Errorf("USB OUT write: %w", err)
	}
	return n, nil
}

func (c *AoaConn) Close() error {
	c.closedOnce.Do(func() {
		close(c.closed)
		c.pw.CloseWithError(io.EOF)
		c.pr.CloseWithError(io.EOF)
		c.done()
		c.dev.Close()
	})
	return nil
}

func (c *AoaConn) SetDeadline(_ time.Time) error      { return nil }
func (c *AoaConn) SetReadDeadline(_ time.Time) error  { return nil }
func (c *AoaConn) SetWriteDeadline(_ time.Time) error { return nil }
func (c *AoaConn) LocalAddr() net.Addr                { return c.localAddr }
func (c *AoaConn) RemoteAddr() net.Addr               { return c.remoteAddr }

type usbAddr struct{ s string }

func (u usbAddr) Network() string { return "usb" }
func (u usbAddr) String() string  { return u.s }

type AoaServer struct {
	cfg   Config
	tun   *TunDevice
	state *ConnectionStateTracker
}

func NewAoaServer(cfg Config, tun *TunDevice) *AoaServer {
	return &AoaServer{
		cfg:   cfg,
		tun:   tun,
		state: NewConnectionStateTracker(),
	}
}

func (s *AoaServer) Run(ctx context.Context) {
	logf("AOA server ready — waiting for Android device over USB")
	usbCtx := gousb.NewContext()
	defer usbCtx.Close()

	for ctx.Err() == nil {
		s.state.Set(StateDetecting)
		conn, err := s.connectWithBackoff(ctx, usbCtx)
		if err != nil {
			if ctx.Err() != nil {
				s.state.Set(StateStopping)
				return
			}
			s.state.Fail(err)
			errorf("AOA connection failed: %v", err)
			continue
		}

		s.state.Set(StateConnected)
		logf("AOA: Android device connected — starting session")
		sess := newSession(conn, s.tun, s.cfg)
		sess.run(ctx)
		conn.Close()
		s.state.Set(StateDisconnected)
		logf("AOA: session ended — waiting for reconnect")
	}
}

func (s *AoaServer) connectWithBackoff(ctx context.Context, usbCtx *gousb.Context) (*AoaConn, error) {
	var lastErr error

	for attempt := 1; attempt <= aoaMaxAttempts; attempt++ {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}

		if conn, err := s.openAccessory(usbCtx); err == nil {
			return conn, nil
		} else {
			lastErr = err
		}

		if err := s.negotiateAccessory(usbCtx); err == nil {
			// Give Android time to re-enumerate as an accessory, but keep the
			// wait interruptible so shutdown is immediate.
			if err := waitContext(ctx, aoaDetectWait); err != nil {
				return nil, err
			}
			if conn, err := s.openAccessory(usbCtx); err == nil {
				return conn, nil
			} else {
				lastErr = err
			}
		} else {
			lastErr = fmt.Errorf("AOA negotiation: %w", err)
		}

		backoff := aoaBackoffMin << (attempt - 1)
		if backoff > aoaBackoffMax {
			backoff = aoaBackoffMax
		}
		debugf("AOA detection attempt %d/%d failed: %v; retrying in %s", attempt, aoaMaxAttempts, lastErr, backoff)
		if err := waitContext(ctx, backoff); err != nil {
			return nil, err
		}
	}

	return nil, fmt.Errorf("AOA connection unavailable after %d attempts: %w", aoaMaxAttempts, lastErr)
}

func waitContext(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func (s *AoaServer) openAccessory(usbCtx *gousb.Context) (*AoaConn, error) {
	devs, err := usbCtx.OpenDevices(func(desc *gousb.DeviceDesc) bool {
		return desc.Vendor == googleVID &&
			(desc.Product == aoaPID || desc.Product == aoaADBPID)
	})
	if err != nil || len(devs) == 0 {
		for _, d := range devs {
			d.Close()
		}
		return nil, fmt.Errorf("no OpenTether accessory detected")
	}
	dev := devs[0]
	for _, d := range devs[1:] {
		d.Close()
	}

	dev.SetAutoDetach(true)
	intf, done, err := dev.DefaultInterface()
	if err != nil {
		dev.Close()
		return nil, fmt.Errorf("open default interface: %w", err)
	}

	var inEp *gousb.InEndpoint
	var outEp *gousb.OutEndpoint
	for _, ep := range intf.Setting.Endpoints {
		if ep.Direction == gousb.EndpointDirectionIn && ep.TransferType == gousb.TransferTypeBulk {
			inEp, _ = intf.InEndpoint(ep.Number)
		}
		if ep.Direction == gousb.EndpointDirectionOut && ep.TransferType == gousb.TransferTypeBulk {
			outEp, _ = intf.OutEndpoint(ep.Number)
		}
	}

	if inEp == nil || outEp == nil {
		done()
		dev.Close()
		return nil, fmt.Errorf("bulk endpoints not found on accessory device")
	}

	serial, _ := dev.SerialNumber()
	logf("AOA: accessory detected (serial=%s, IN ep%d, OUT ep%d)", serial, inEp.Desc.Number, outEp.Desc.Number)

	pr, pw := io.Pipe()
	conn := &AoaConn{
		dev:        dev,
		intf:       intf,
		done:       done,
		in:         inEp,
		out:        outEp,
		pr:         pr,
		pw:         pw,
		localAddr:  usbAddr{"relay"},
		remoteAddr: usbAddr{serial},
		closed:     make(chan struct{}),
	}
	go conn.readPump()
	return conn, nil
}

func (s *AoaServer) negotiateAccessory(usbCtx *gousb.Context) error {
	devs, err := usbCtx.OpenDevices(func(desc *gousb.DeviceDesc) bool {
		for _, vid := range androidVIDs {
			if desc.Vendor == vid {
				return true
			}
		}
		return false
	})
	if err != nil || len(devs) == 0 {
		return fmt.Errorf("no Android device found")
	}
	dev := devs[0]
	for _, d := range devs[1:] {
		d.Close()
	}
	defer dev.Close()

	const (
		ctrlIn  = uint8(gousb.ControlIn) | uint8(gousb.ControlVendor) | uint8(gousb.ControlDevice)
		ctrlOut = uint8(gousb.ControlOut) | uint8(gousb.ControlVendor) | uint8(gousb.ControlDevice)
	)

	protoBuf := make([]byte, 2)
	_, err = dev.Control(ctrlIn, aoaGetProtocol, 0, 0, protoBuf)
	if err != nil {
		return fmt.Errorf("ACCESSORY_GET_PROTOCOL: %w", err)
	}
	version := uint16(protoBuf[1])<<8 | uint16(protoBuf[0])
	if version == 0 {
		return fmt.Errorf("device does not support AOA (protocol version 0)")
	}
	debugf("AOA: device supports protocol version %d", version)

	for idx, str := range aoaStrings {
		_, err = dev.Control(ctrlOut, aoaSendString, 0, uint16(idx), []byte(str+"\x00"))
		if err != nil {
			return fmt.Errorf("ACCESSORY_SEND_STRING idx=%d: %w", idx, err)
		}
	}

	_, err = dev.Control(ctrlOut, aoaStartMode, 0, 0, nil)
	if err != nil {
		return fmt.Errorf("ACCESSORY_START: %w", err)
	}
	logf("AOA: negotiation complete — Android is switching to accessory mode")
	return nil
}
