//go:build linux

package relay

import (
	"fmt"
	"io"
	"syscall"
	"unsafe"
)

// Linux ioctl constants for TUN/TAP
const (
	// TUNSETIFF = _IOW('T', 202, int) on x86_64 Linux
	// Computed: (1<<30) | ('T'<<8) | 202 | (4<<16) = 0x400454CA
	tunSetIff uintptr = 0x400454CA

	iffTUN     uint16 = 0x0001 // TUN device (not TAP)
	iffNOPI    uint16 = 0x1000 // suppress 4-byte packet-info header on reads
	iffTUNEXCL uint16 = 0x8000 // fail if the named interface already exists

	ifNameSize = 16 // IFNAMSIZ including null terminator
)

// tunIFFlags returns the flags used for TUNSETIFF. IFF_TUN_EXCL prevents this
// process from attaching to an interface owned by another relay instance: a
// shared TUN would steal packets and cleanup would tear down the other
// instance's networking.
func tunIFFlags() uint16 {
	return iffTUN | iffNOPI | iffTUNEXCL
}

// TunDevice wraps a Linux TUN file descriptor.
// Read returns raw IPv4/IPv6 packets (no frame header).
// Write injects raw IP packets into the kernel's network stack.
type TunDevice struct {
	fd   int
	name string

	// writeFn is syscall.Write in production. Tests inject a short-write
	// implementation; nil falls back to syscall.Write.
	writeFn func(int, []byte) (int, error)
}

// OpenTUN creates or opens a TUN interface with the given name.
// Requires CAP_NET_ADMIN (run as root, or: sudo setcap cap_net_admin+ep ./relay-bin).
func OpenTUN(name string) (*TunDevice, error) {
	// Step 1: open the TUN/TAP character device
	fd, err := syscall.Open(
		"/dev/net/tun",
		syscall.O_RDWR|syscall.O_CLOEXEC,
		0,
	)
	if err != nil {
		return nil, fmt.Errorf(
			"open /dev/net/tun: %w\n"+
				"  → ensure the tun module is loaded: sudo modprobe tun\n"+
				"  → ensure you have CAP_NET_ADMIN (run with sudo or setcap)",
			err,
		)
	}

	// Step 2: TUNSETIFF — configure as TUN with no packet-info prefix
	// struct ifreq layout: 16-byte name, then a union; flags at offset 16
	var ifr [40]byte
	copy(ifr[:ifNameSize], []byte(name)) // null-padded automatically
	*(*uint16)(unsafe.Pointer(&ifr[ifNameSize])) = tunIFFlags()

	if _, _, errno := syscall.Syscall(
		syscall.SYS_IOCTL,
		uintptr(fd),
		tunSetIff,
		uintptr(unsafe.Pointer(&ifr[0])),
	); errno != 0 {
		syscall.Close(fd)
		if errno == syscall.EBUSY {
			return nil, fmt.Errorf("TUNSETIFF ioctl: %w — interface %q is already owned by another process", errno, name)
		}
		return nil, fmt.Errorf("TUNSETIFF ioctl: %w", errno)
	}

	// Read back the actual name the kernel assigned
	actualName := cString(ifr[:ifNameSize])
	if actualName == "" {
		actualName = name
	}

	return &TunDevice{fd: fd, name: actualName, writeFn: syscall.Write}, nil
}

func (t *TunDevice) Name() string { return t.name }
func (t *TunDevice) Fd() int      { return t.fd }

// Read returns one raw IP packet (blocks until a packet arrives).
func (t *TunDevice) Read(p []byte) (int, error) {
	n, err := syscall.Read(t.fd, p)
	if err != nil {
		return 0, fmt.Errorf("tun read: %w", err)
	}
	return n, nil
}

// Write injects one raw IP packet into the kernel network stack.
func (t *TunDevice) Write(p []byte) (int, error) {
	write := t.writeFn
	if write == nil {
		write = syscall.Write
	}
	n, err := write(t.fd, p)
	if err != nil {
		return 0, fmt.Errorf("tun write: %w", err)
	}
	if n != len(p) {
		// A partial packet injection would silently drop the rest of the IP
		// packet. Fail the session instead.
		return n, fmt.Errorf("tun write: %w", io.ErrShortWrite)
	}
	return n, nil
}

func (t *TunDevice) Close() error {
	return syscall.Close(t.fd)
}

// cString converts a null-terminated byte slice to a Go string.
func cString(b []byte) string {
	for i, c := range b {
		if c == 0 {
			return string(b[:i])
		}
	}
	return string(b)
}
