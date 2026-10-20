package relay

import (
	"fmt"
	"net"
	"strconv"
	"strings"
)

// Supported tunnel limits. These are not arbitrary: the stock Android client
// hard-codes 10.0.0.1/24 and a 1400-byte VPN MTU, and it also routes IPv6,
// whose minimum link MTU is 1280 bytes.
const (
	MinTunnelPrefix = 24
	MaxTunnelPrefix = 30
	MinMTU          = 1280
	MaxMTU          = 1400
)

// Config holds all relay configuration. Zero value is not valid;
// use the defaults in main.go.
type Config struct {
	ListenAddr string
	TunName    string
	TunAddr    string
	TunPrefix  int
	AndroidIP  string
	OutIface   string
	MTU        int

	// DisableAdbWatch disables automatic `adb reverse` setup.
	// Set to true if you prefer to run `adb reverse` manually or
	// if adb is not installed on the PC.
	DisableAdbWatch bool

	// AdbBin is the ADB executable used by the automatic reverse-setup
	// watcher. Empty means "adb" (resolved via PATH). Point it at a full
	// path — for example the Windows adb.exe invoked from WSL — when the
	// default executable's ADB server cannot see the device.
	AdbBin string

	// AdbPort is the ADB server port the watcher targets via the ADB
	// client's -P flag. Zero keeps the ADB client's default server port
	// behaviour (usually 5037).
	AdbPort int

	// AOAMode switches the session write path from FlushWriter (TCP batching)
	// to DirectWriter (one USB transfer per OTP frame).
	//
	// Set automatically by RunAOA; do NOT set this manually for TCP mode.
	//
	// Background: USB bulk OUT transfers are discrete, not a byte stream.
	// Android's /dev/usb_accessory driver returns one USB transfer per read()
	// syscall. Batching multiple OTP frames into one epOut.Write call causes
	// framing corruption on Android because DataInputStream.readFully may not
	// span USB transfer boundaries on all devices.
	AOAMode bool

	Verbose bool
}

// Validate rejects configurations that would panic, silently truncate packets,
// or expose an unauthenticated packet-injection listener before any TUN device
// or host networking is touched.
//
// Validate normalises TunAddr and AndroidIP to canonical dotted-quad IPv4. It
// therefore takes a pointer: callers must keep using the same Config value
// afterwards so Linux networking commands receive the normalised addresses.
func (c *Config) Validate() error {
	if c.MTU < MinMTU || c.MTU > MaxMTU {
		return fmt.Errorf("MTU %d is outside the supported range %d..%d (the stock Android client uses %d)", c.MTU, MinMTU, MaxMTU, MaxMTU)
	}
	if c.AdbPort < 0 || c.AdbPort > 65535 {
		return fmt.Errorf("adb server port %d is outside the valid range 0..65535 (0 = ADB client default)", c.AdbPort)
	}
	if c.TunPrefix < MinTunnelPrefix || c.TunPrefix > MaxTunnelPrefix {
		return fmt.Errorf("TUN prefix /%d is outside the supported /%d../%d range", c.TunPrefix, MinTunnelPrefix, MaxTunnelPrefix)
	}
	if n := len(c.TunName); n == 0 || n > 15 {
		return fmt.Errorf("TUN name %q must be 1..15 bytes", c.TunName)
	}

	tunIP, err := parseTunnelEndpoint("TUN address", c.TunAddr)
	if err != nil {
		return err
	}
	androidIP, err := parseTunnelEndpoint("Android address", c.AndroidIP)
	if err != nil {
		return err
	}
	if tunIP.Equal(androidIP) {
		return fmt.Errorf("TUN address and Android address must differ (both %q)", c.TunAddr)
	}

	// Derive the tunnel subnet from the relay address and require both peers to
	// be inside it. This keeps the /32 Android route and the TUN address on the
	// same link and rejects internally inconsistent endpoint/subnet
	// combinations. The stock client additionally requires its fixed
	// 10.0.0.1/24; custom endpoints need a matching client build.
	_, subnet, err := net.ParseCIDR(fmt.Sprintf("%s/%d", tunIP, c.TunPrefix))
	if err != nil {
		return fmt.Errorf("invalid TUN subnet %s/%d: %w", tunIP, c.TunPrefix, err)
	}
	if !subnet.Contains(tunIP) {
		return fmt.Errorf("TUN address %q is not inside %s", tunIP, subnet)
	}
	if !subnet.Contains(androidIP) {
		return fmt.Errorf("Android address %q is not in the TUN subnet %s", androidIP, subnet)
	}
	if isNetworkOrBroadcast(tunIP, subnet) {
		return fmt.Errorf("TUN address %q is the network or broadcast address of %s", tunIP, subnet)
	}
	if isNetworkOrBroadcast(androidIP, subnet) {
		return fmt.Errorf("Android address %q is the network or broadcast address of %s", androidIP, subnet)
	}

	// Canonical dotted-quad form for `ip` and `iptables` arguments.
	c.TunAddr = tunIP.String()
	c.AndroidIP = androidIP.String()

	if c.ListenAddr == "" {
		if c.AOAMode {
			return nil // AOA does not create a TCP listener.
		}
		return fmt.Errorf("listen address is required in TCP mode")
	}
	return validateLoopbackListenAddr(c.ListenAddr)
}

func parseTunnelEndpoint(name, raw string) (net.IP, error) {
	// net.ParseIP accepts IPv4-mapped IPv6 forms such as ::ffff:10.0.0.2.
	// Those are ambiguous in ip/iptables arguments and are not a supported
	// tunnel endpoint representation.
	if strings.Contains(raw, ":") {
		return nil, fmt.Errorf("%s %q must be an IPv4 address (IPv6 and IPv4-mapped values are not supported)", name, raw)
	}

	ip := net.ParseIP(raw)
	if ip == nil {
		return nil, fmt.Errorf("%s %q is not a valid IPv4 address", name, raw)
	}
	ip4 := ip.To4()
	if ip4 == nil {
		return nil, fmt.Errorf("%s %q is not a valid IPv4 address", name, raw)
	}

	switch {
	case ip4.IsUnspecified():
		return nil, fmt.Errorf("%s %q must not be the unspecified address", name, raw)
	case ip4.IsLoopback():
		return nil, fmt.Errorf("%s %q must not be a loopback address", name, raw)
	case ip4.IsMulticast():
		return nil, fmt.Errorf("%s %q must not be a multicast address", name, raw)
	case ip4.IsLinkLocalUnicast():
		return nil, fmt.Errorf("%s %q must not be a link-local address", name, raw)
	case ip4.Equal(net.IPv4bcast):
		return nil, fmt.Errorf("%s %q must not be the broadcast address", name, raw)
	}

	return ip4, nil
}

func isNetworkOrBroadcast(ip net.IP, subnet *net.IPNet) bool {
	ip4 := ip.To4()
	base := subnet.IP.To4()
	if ip4 == nil || base == nil || len(subnet.Mask) != net.IPv4len {
		return false
	}
	if ip4.Equal(base) {
		return true
	}

	broadcast := make(net.IP, net.IPv4len)
	for i := range broadcast {
		broadcast[i] = base[i] | ^subnet.Mask[i]
	}
	return ip4.Equal(broadcast)
}

// validateLoopbackListenAddr requires an explicit loopback IP literal in
// host:port form. Hostnames are rejected because their resolution is not
// guaranteed to stay on loopback, and wildcard addresses would expose the
// unauthenticated OTP packet injector on every interface.
func validateLoopbackListenAddr(addr string) error {
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("invalid listen address %q: %w", addr, err)
	}
	if host == "" {
		return fmt.Errorf("listen address %q must include an explicit loopback IP, not a wildcard", addr)
	}
	if strings.Contains(host, ":") {
		// SplitHostPort removes IPv6 brackets, so a colon here means IPv6.
		if ip := net.ParseIP(host); ip != nil && ip.To4() != nil {
			return fmt.Errorf("listen address %q must not use an IPv4-mapped IPv6 address", addr)
		}
	}

	ip := net.ParseIP(host)
	if ip == nil {
		return fmt.Errorf("listen host %q must be an explicit loopback IP literal (hostnames are not accepted)", host)
	}
	if !ip.IsLoopback() {
		return fmt.Errorf("listen host %q is not a loopback address", host)
	}
	if ip.To4() == nil && !ip.Equal(net.IPv6loopback) {
		return fmt.Errorf("listen host %q is not the IPv6 loopback address", host)
	}

	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("invalid listen port %q", portStr)
	}
	return nil
}
