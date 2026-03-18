//go:build linux

package relay

import (
	"fmt"
	"syscall"
	"unsafe"
)

// Linux ioctl constants for TUN/TAP
const (
	// TUNSETIFF = _IOW('T', 202, int) on x86_64 Linux
	// Computed: (1<<30) | ('T'<<8) | 202 | (4<<16) = 0x400454CA
	tunSetIff uintptr = 0x400454CA

	iffTUN  uint16 = 0x0001 // TUN device (not TAP)
	iffNOPI uint16 = 0x1000 // suppress 4-byte packet-info header on reads

	ifNameSize = 16 // IFNAMSIZ including null terminator
)

// TunDevice wraps a Linux TUN file descriptor.
// Read returns raw IPv4/IPv6 packets (no frame header).
// Write injects raw IP packets into the kernel's network stack.
type TunDevice struct {
	fd   int
	name string
}

// OpenTUN creates or opens a TUN interface with the given name.
// Requires CAP_NET_ADMIN (run as root, or: sudo setcap cap_net_admin+ep ./relay).
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
	*(*uint16)(unsafe.Pointer(&ifr[ifNameSize])) = iffTUN | iffNOPI

	if _, _, errno := syscall.Syscall(
		syscall.SYS_IOCTL,
		uintptr(fd),
		tunSetIff,
		uintptr(unsafe.Pointer(&ifr[0])),
	); errno != 0 {
		syscall.Close(fd)
		return nil, fmt.Errorf("TUNSETIFF ioctl: %w", errno)
	}

	// Read back the actual name the kernel assigned
	actualName := cString(ifr[:ifNameSize])
	if actualName == "" {
		actualName = name
	}

	return &TunDevice{fd: fd, name: actualName}, nil
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
	n, err := syscall.Write(t.fd, p)
	if err != nil {
		return 0, fmt.Errorf("tun write: %w", err)
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