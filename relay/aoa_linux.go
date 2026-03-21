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

// ─── AOA protocol constants ────────────────────────────────────────────────

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

	// aoaReadBufSize is the size of each USB bulk IN transfer submitted by the
	// read pump. This must not exceed Android's internal USB accessory transfer
	// limit (typically 16 KB). Using a value equal to the OTP frame max size
	// (12-byte header + 65535-byte payload) would be wasteful; 16 KB gives
	// enough headroom for one or two batched frames without over-requesting.
	//
	// The original code called epIn.Read(p) directly from the bufio fill path,
	// which submitted transfers of up to 6000 bytes (MTU*4). In practice, gousb
	// + libusb handle short-packet termination correctly, but capping the read
	// here to 16 KB makes behaviour independent of the caller's buffer size and
	// avoids any libusb/kernel short-packet edge cases on unusual USB host
	// controllers.
	aoaReadBufSize = 16 * 1024
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
	0x04E8, // Samsung
	0x18D1, // Google / Pixel
	0x2717, // Xiaomi
	0x12D1, // Huawei
	0x1BBB, // Motorola
	0x0BB4, // HTC
	0x054C, // Sony
	0x2A70, // OnePlus
	0x22D9, // OPPO / Realme
	0x19D2, // ZTE
}

// ─── AoaConn ──────────────────────────────────────────────────────────────

// AoaConn wraps two USB bulk endpoints and presents them as a net.Conn so
// the existing session logic can use it without modification.
//
// Read path — read pump goroutine
// ────────────────────────────────
// The original code called epIn.Read(p) directly from within bufio's fill
// path. bufio asked for up to 6000 bytes per call, which caused gousb to
// submit a 6000-byte USB bulk IN transfer. While short-packet termination
// should complete such a transfer the moment Android sends any data, this
// relies on correct behaviour from libusb and the specific USB host
// controller driver.
//
// Instead, a dedicated readPump goroutine reads from epIn in a tight loop,
// each time requesting at most aoaReadBufSize bytes. The data is fed into an
// io.Pipe, and AoaConn.Read reads from the pipe's consumer end. This decouples
// the caller's buffer size from the USB transfer size and makes the read
// behaviour fully deterministic.
//
// Write path
// ──────────
// epOut.Write is called directly from FlushWriter (which serialises writes
// with a mutex). No change needed here; USB bulk OUT is inherently ordered
// and gousb handles splitting writes into multiple USB packets as needed.
type AoaConn struct {
	dev        *gousb.Device
	intf       *gousb.Interface
	done       func() // releases the interface
	in         *gousb.InEndpoint
	out        *gousb.OutEndpoint
	pr         *io.PipeReader // AoaConn.Read reads from here
	pw         *io.PipeWriter // readPump writes to here
	localAddr  net.Addr
	remoteAddr net.Addr
	closedOnce sync.Once
	closed     chan struct{}
}

// readPump runs in its own goroutine for the lifetime of the connection.
// It reads from the USB bulk IN endpoint in aoaReadBufSize chunks and
// forwards data into the pipe. When the endpoint closes or the connection
// is torn down, it closes the write end of the pipe so AoaConn.Read returns
// io.EOF.
func (c *AoaConn) readPump() {
	buf := make([]byte, aoaReadBufSize)
	for {
		n, err := c.in.Read(buf)
		if n > 0 {
			// Write whatever arrived into the pipe before checking err.
			// A short read with valid data is normal for USB streaming.
			if _, werr := c.pw.Write(buf[:n]); werr != nil {
				// Pipe was closed — session ended cleanly.
				return
			}
		}
		if err != nil {
			select {
			case <-c.closed:
				// Close() was called; this is expected.
				c.pw.CloseWithError(io.EOF)
			default:
				c.pw.CloseWithError(fmt.Errorf("USB IN read: %w", err))
			}
			return
		}
	}
}

// Read satisfies io.Reader. It reads from the pipe that readPump fills.
// This call blocks until data is available, just like a TCP socket read.
func (c *AoaConn) Read(p []byte) (int, error) {
	return c.pr.Read(p)
}

// Write satisfies io.Writer. It writes directly to the USB bulk OUT endpoint.
// Called exclusively by FlushWriter (which holds a mutex), so no additional
// synchronisation is needed here.
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

// Close tears down the connection, stops readPump, releases the USB interface,
// and closes the device. Safe to call multiple times.
func (c *AoaConn) Close() error {
	c.closedOnce.Do(func() {
		close(c.closed)
		// Closing pw causes readPump's next pipe write to fail, stopping it.
		// Closing pr causes any in-progress AoaConn.Read to return immediately.
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

// ─── AoaServer ────────────────────────────────────────────────────────────

type AoaServer struct {
	cfg Config
	tun *TunDevice
}

func NewAoaServer(cfg Config, tun *TunDevice) *AoaServer {
	return &AoaServer{cfg: cfg, tun: tun}
}

// Run loops waiting for Android devices. Each connection is handled
// in-line (one device at a time). Blocks until ctx is cancelled.
func (s *AoaServer) Run(ctx context.Context) {
	logf("AOA server ready — waiting for Android device over USB")
	usbCtx := gousb.NewContext()
	defer usbCtx.Close()

	for ctx.Err() == nil {
		conn, err := s.waitForDevice(ctx, usbCtx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			errorf("AOA: %v — retrying in 3 s", err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
			}
			continue
		}

		logf("AOA: Android device connected — starting session")
		sess := newSession(conn, s.tun, s.cfg)
		sess.run(ctx)
		logf("AOA: session ended — waiting for reconnect")
		conn.Close()
	}
}

func (s *AoaServer) waitForDevice(ctx context.Context, usbCtx *gousb.Context) (*AoaConn, error) {
	for ctx.Err() == nil {
		if conn, err := s.openAccessory(usbCtx); err == nil {
			return conn, nil
		}
		if err := s.negotiateAccessory(usbCtx); err != nil {
			debugf("AOA negotiate: %v", err)
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(2500 * time.Millisecond):
		}
	}
	return nil, ctx.Err()
}

// openAccessory tries to open an already-enumerated AOA device.
func (s *AoaServer) openAccessory(usbCtx *gousb.Context) (*AoaConn, error) {
	devs, err := usbCtx.OpenDevices(func(desc *gousb.DeviceDesc) bool {
		return desc.Vendor == googleVID &&
			(desc.Product == aoaPID || desc.Product == aoaADBPID)
	})
	if err != nil || len(devs) == 0 {
		for _, d := range devs {
			d.Close()
		}
		return nil, fmt.Errorf("no accessory device found")
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
	logf("AOA: opened accessory device (serial=%s, IN ep%d, OUT ep%d)",
		serial, inEp.Desc.Number, outEp.Desc.Number)

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
	// Start the read pump. It runs until Close() is called or the USB device
	// disconnects. All data from the Android device flows through it.
	go conn.readPump()
	return conn, nil
}

// negotiateAccessory finds any Android device and switches it into AOA mode.
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
		ctrlIn  = uint8(gousb.ControlIn)  | uint8(gousb.ControlVendor) | uint8(gousb.ControlDevice)
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
