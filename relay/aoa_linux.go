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
	// Control request bRequest values (AOA 1.0 / 2.0)
	aoaGetProtocol uint8 = 51 // 0x33 — query supported AOA version
	aoaSendString  uint8 = 52 // 0x34 — send one identification string
	aoaStartMode   uint8 = 53 // 0x35 — switch device into accessory mode

	// String index values for aoaSendString
	aoaIdxManufacturer = 0
	aoaIdxModel        = 1
	aoaIdxDescription  = 2
	aoaIdxVersion      = 3
	aoaIdxURI          = 4
	aoaIdxSerial       = 5

	// Google USB identifiers when device is in accessory mode
	googleVID    gousb.ID = 0x18D1
	aoaPID       gousb.ID = 0x2D00 // accessory only
	aoaADBPID    gousb.ID = 0x2D01 // accessory + ADB debug
)

// Accessory identification strings. Android shows these in the system dialog
// that appears the first time a device connects. After the user accepts, the
// app is opened automatically on all future connections.
//
// Change URI to match your actual repo / website.
var aoaStrings = [6]string{
	"OpenTether",
	"OpenTether Relay",
	"Reverse USB tethering — no root required",
	"1.0",
	"https://github.com/pyd-07/NetcoN-OpenTether",
	"opentether-relay-01",
}

// Known Android USB vendor IDs (non-exhaustive; extend as needed).
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

// AoaConn wraps two USB bulk endpoints and presents them as an io.ReadWriteCloser
// (and net.Conn) so the existing session logic can use it without modification.
type AoaConn struct {
	dev        *gousb.Device
	intf       *gousb.Interface
	done       func() // releases the interface
	in         *gousb.InEndpoint
	out        *gousb.OutEndpoint
	localAddr  net.Addr
	remoteAddr net.Addr
	closedOnce sync.Once
	closed     chan struct{}
}

func (c *AoaConn) Read(p []byte) (int, error) {
	select {
	case <-c.closed:
		return 0, io.EOF
	default:
	}
	return c.in.Read(p)
}

func (c *AoaConn) Write(p []byte) (int, error) {
	select {
	case <-c.closed:
		return 0, io.ErrClosedPipe
	default:
	}
	return c.out.Write(p)
}

func (c *AoaConn) Close() error {
	c.closedOnce.Do(func() {
		close(c.closed)
		c.done()
		c.dev.Close()
	})
	return nil
}

// net.Conn deadline stubs — AOA bulk transfers don't support per-call deadlines.
// The session cancellation via context.WithCancel handles timeout instead.
func (c *AoaConn) SetDeadline(_ time.Time) error      { return nil }
func (c *AoaConn) SetReadDeadline(_ time.Time) error  { return nil }
func (c *AoaConn) SetWriteDeadline(_ time.Time) error { return nil }
func (c *AoaConn) LocalAddr() net.Addr                { return c.localAddr }
func (c *AoaConn) RemoteAddr() net.Addr               { return c.remoteAddr }

// usbAddr satisfies net.Addr for logging.
type usbAddr struct{ s string }

func (u usbAddr) Network() string { return "usb" }
func (u usbAddr) String() string  { return u.s }

// ─── AoaServer ────────────────────────────────────────────────────────────

// AoaServer scans for Android USB devices and establishes AOA sessions.
// It is an alternative to the TCP server and does not require `adb reverse`.
//
// Usage (replace or complement Server in main.go):
//
//	aoaSrv := relay.NewAoaServer(cfg, tun)
//	go aoaSrv.Run(ctx)
type AoaServer struct {
	cfg Config
	tun *TunDevice
}

// NewAoaServer returns an AoaServer. The TUN device must already be configured
// by NetworkSetup before calling Run.
func NewAoaServer(cfg Config, tun *TunDevice) *AoaServer {
	return &AoaServer{cfg: cfg, tun: tun}
}

// Run loops, waiting for Android devices. Each connection is handled in-line
// (one device at a time — AOA is 1:1). Blocks until ctx is cancelled.
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
		sess.run(ctx) // blocks until device disconnects
		logf("AOA: session ended — waiting for reconnect")
		conn.Close()
	}
}

// waitForDevice scans for an Android device, negotiates AOA, and returns a
// ready AoaConn. Blocks until a device appears or ctx is cancelled.
func (s *AoaServer) waitForDevice(ctx context.Context, usbCtx *gousb.Context) (*AoaConn, error) {
	for ctx.Err() == nil {
		// First, try to open a device that is already in accessory mode.
		if conn, err := s.openAccessory(usbCtx); err == nil {
			return conn, nil
		}

		// Otherwise, scan for any Android device and negotiate AOA.
		if err := s.negotiateAccessory(usbCtx); err != nil {
			debugf("AOA negotiate: %v", err)
		}

		// After negotiation Android reboots into accessory mode (~2 s).
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(2500 * time.Millisecond):
		}
	}
	return nil, ctx.Err()
}

// openAccessory tries to open an existing AOA device (VID=0x18D1, PID=0x2D00/01).
func (s *AoaServer) openAccessory(usbCtx *gousb.Context) (*AoaConn, error) {
	devs, err := usbCtx.OpenDevices(func(desc *gousb.DeviceDesc) bool {
		return desc.Vendor == googleVID &&
			(desc.Product == aoaPID || desc.Product == aoaADBPID)
	})
	if err != nil || len(devs) == 0 {
		// Close any extra devices if more than one appeared (shouldn't happen).
		for _, d := range devs {
			d.Close()
		}
		return nil, fmt.Errorf("no accessory device found")
	}
	// Use the first one; close any extras.
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
	logf("AOA: opened accessory device (serial=%s)", serial)

	return &AoaConn{
		dev:        dev,
		intf:       intf,
		done:       done,
		in:         inEp,
		out:        outEp,
		localAddr:  usbAddr{"relay"},
		remoteAddr: usbAddr{serial},
		closed:     make(chan struct{}),
	}, nil
}

// negotiateAccessory finds any Android device, checks AOA support, sends the
// identification strings, and issues the "start accessory" command.
// After this returns, Android will disconnect and reconnect in accessory mode.
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
		return fmt.Errorf("no Android device found (check USB debugging is enabled)")
	}
	dev := devs[0]
	for _, d := range devs[1:] {
		d.Close()
	}
	defer dev.Close()

	// ctrlIn / ctrlOut are the USB bmRequestType bytes for vendor control
	// transfers targeting the device as a whole.
	//
	// gousb exposes ControlIn, ControlOut, ControlVendor, and ControlDevice
	// as distinct named types (not plain uint8), so they cannot be OR-combined
	// directly. Casting each constant to uint8 first produces the correct
	// bmRequestType value — identical to what the USB spec requires.
	const (
		ctrlIn  = uint8(gousb.ControlIn)  | uint8(gousb.ControlVendor) | uint8(gousb.ControlDevice)
		ctrlOut = uint8(gousb.ControlOut) | uint8(gousb.ControlVendor) | uint8(gousb.ControlDevice)
	)

	// Step 1: query protocol version.
	protoBuf := make([]byte, 2)
	_, err = dev.Control(
		ctrlIn,
		aoaGetProtocol,
		0, 0,
		protoBuf,
	)
	if err != nil {
		return fmt.Errorf("ACCESSORY_GET_PROTOCOL: %w", err)
	}
	version := uint16(protoBuf[1])<<8 | uint16(protoBuf[0])
	if version == 0 {
		return fmt.Errorf("device does not support AOA (protocol version 0)")
	}
	debugf("AOA: device supports protocol version %d", version)

	// Step 2: send identification strings.
	for idx, str := range aoaStrings {
		_, err = dev.Control(
			ctrlOut,
			aoaSendString,
			0, uint16(idx),
			[]byte(str+"\x00"),
		)
		if err != nil {
			return fmt.Errorf("ACCESSORY_SEND_STRING idx=%d: %w", idx, err)
		}
	}

	// Step 3: tell the device to switch into accessory mode.
	// This causes the device to disconnect and reconnect.
	_, err = dev.Control(
		ctrlOut,
		aoaStartMode,
		0, 0,
		nil,
	)
	if err != nil {
		return fmt.Errorf("ACCESSORY_START: %w", err)
	}
	logf("AOA: negotiation complete — Android is switching to accessory mode")
	return nil
}