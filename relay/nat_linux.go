//go:build linux

package relay

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strings"
)

// NetworkSetup configures the TUN interface and iptables rules.
// Call Cleanup() on shutdown to remove all rules added by this setup.
type NetworkSetup struct {
	cfg      Config
	OutIface string    // resolved outbound interface name
	undoList []func()  // cleanup functions in reverse order
}

// NewNetworkSetup configures the OS for relaying:
//  1. Assigns IP to TUN interface
//  2. Brings TUN up
//  3. Adds route for Android IP via TUN
//  4. Enables IPv4 forwarding
//  5. Adds MASQUERADE iptables rule
func NewNetworkSetup(cfg Config, tun *TunDevice) (*NetworkSetup, error) {
	ns := &NetworkSetup{cfg: cfg}

	// Resolve outbound interface
	outIface := cfg.OutIface
	if outIface == "" {
		var err error
		outIface, err = detectDefaultInterface()
		if err != nil {
			return nil, fmt.Errorf("detect default interface: %w", err)
		}
	}
	ns.OutIface = outIface
	logf("outbound interface: %s", outIface)

	tunName := tun.Name()
	cidr := fmt.Sprintf("%s/%d", cfg.TunAddr, cfg.TunPrefix)
	androidCIDR := cfg.AndroidIP + "/32"

	// Assign IP address to TUN
	if err := runCmd("ip", "addr", "replace", cidr, "dev", tunName); err != nil {
		return nil, fmt.Errorf("ip addr: %w", err)
	}
	ns.addUndo(func() { runCmd("ip", "addr", "del", cidr, "dev", tunName) })

	// Set MTU
	if err := runCmd("ip", "link", "set", "dev", tunName, "mtu",
		fmt.Sprintf("%d", cfg.MTU)); err != nil {
		return nil, fmt.Errorf("set mtu: %w", err)
	}

	// Bring interface up
	if err := runCmd("ip", "link", "set", "dev", tunName, "up"); err != nil {
		return nil, fmt.Errorf("ip link up: %w", err)
	}
	ns.addUndo(func() { runCmd("ip", "link", "set", "dev", tunName, "down") })

	// Add route for Android IP through TUN (replace = idempotent)
	if err := runCmd("ip", "route", "replace", androidCIDR, "dev", tunName); err != nil {
		return nil, fmt.Errorf("ip route: %w", err)
	}
	ns.addUndo(func() { runCmd("ip", "route", "del", androidCIDR, "dev", tunName) })

	// Enable IPv4 forwarding
	if err := os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1\n"), 0644); err != nil {
		return nil, fmt.Errorf("enable ip_forward: %w", err)
	}
	if err := ipt("-t", "mangle", "-A", "FORWARD",
		"-p", "tcp", "--tcp-flags", "SYN,RST", "SYN",
		"-j", "TCPMSS", "--clamp-mss-to-pmtu"); err != nil {
		return nil, fmt.Errorf("iptables MSS clamp: %w", err)
	}
	ns.addUndo(func() {
		ipt("-t", "mangle", "-D", "FORWARD",
			"-p", "tcp", "--tcp-flags", "SYN,RST", "SYN",
			"-j", "TCPMSS", "--clamp-mss-to-pmtu")
	})
	// Note: we don't disable ip_forward on cleanup — it may have been enabled
	// by something else (e.g. Docker). The user can disable manually if needed.

	// iptables MASQUERADE — all traffic from Android gets SNAT to PC's real IP
	if err := ipt("-t", "nat", "-A", "POSTROUTING",
		"-s", androidCIDR, "-o", outIface, "-j", "MASQUERADE"); err != nil {
		return nil, fmt.Errorf("iptables MASQUERADE: %w", err)
	}
	ns.addUndo(func() {
		ipt("-t", "nat", "-D", "POSTROUTING",
			"-s", androidCIDR, "-o", outIface, "-j", "MASQUERADE")
	})

	// Allow forwarding through TUN in both directions
	if err := ipt("-A", "FORWARD", "-i", tunName, "-j", "ACCEPT"); err != nil {
		return nil, fmt.Errorf("iptables FORWARD in: %w", err)
	}
	ns.addUndo(func() { ipt("-D", "FORWARD", "-i", tunName, "-j", "ACCEPT") })

	if err := ipt("-A", "FORWARD", "-o", tunName, "-j", "ACCEPT"); err != nil {
		return nil, fmt.Errorf("iptables FORWARD out: %w", err)
	}
	ns.addUndo(func() { ipt("-D", "FORWARD", "-o", tunName, "-j", "ACCEPT") })

	// ── IPv6 forwarding + NAT ─────────────────────────────────────────────
	// The Android VPN client captures all IPv6 traffic (addRoute("::", 0)).
	// Without ip6tables MASQUERADE those packets arrive on the TUN and die.

	// Assign an IPv6 address to the TUN interface.
	// We use a unique-local (fc00::/7) address — no ISP allocation needed.
	tun6CIDR    := "fdcc::2/64"
	android6    := "fdcc::1"
	android6CIDR := android6 + "/128"

	if err := runCmd("ip", "-6", "addr", "replace", tun6CIDR, "dev", tunName); err != nil {
		return nil, fmt.Errorf("ip -6 addr: %w", err)
	}
	ns.addUndo(func() { runCmd("ip", "-6", "addr", "del", tun6CIDR, "dev", tunName) })

	if err := runCmd("ip", "-6", "route", "replace", android6CIDR, "dev", tunName); err != nil {
		return nil, fmt.Errorf("ip -6 route: %w", err)
	}
	ns.addUndo(func() { runCmd("ip", "-6", "route", "del", android6CIDR, "dev", tunName) })

	// Enable IPv6 forwarding
	if err := os.WriteFile(
		"/proc/sys/net/ipv6/conf/all/forwarding", []byte("1\n"), 0644,
	); err != nil {
		return nil, fmt.Errorf("enable ipv6 forwarding: %w", err)
	}

	// ip6tables MASQUERADE — Android's IPv6 traffic leaves via PC's real IP
	if err := ip6t("-t", "nat", "-A", "POSTROUTING",
		"-s", android6CIDR, "-o", outIface, "-j", "MASQUERADE"); err != nil {
		return nil, fmt.Errorf("ip6tables MASQUERADE: %w", err)
	}
	ns.addUndo(func() {
		ip6t("-t", "nat", "-D", "POSTROUTING",
			"-s", android6CIDR, "-o", outIface, "-j", "MASQUERADE")
	})

	if err := ip6t("-A", "FORWARD", "-i", tunName, "-j", "ACCEPT"); err != nil {
		return nil, fmt.Errorf("ip6tables FORWARD in: %w", err)
	}
	ns.addUndo(func() { ip6t("-D", "FORWARD", "-i", tunName, "-j", "ACCEPT") })

	if err := ip6t("-A", "FORWARD", "-o", tunName, "-j", "ACCEPT"); err != nil {
		return nil, fmt.Errorf("ip6tables FORWARD out: %w", err)
	}
	ns.addUndo(func() { ip6t("-D", "FORWARD", "-o", tunName, "-j", "ACCEPT") })

	logf("IPv6 NAT configured: TUN=%s android=%s outbound=%s", tun6CIDR, android6, outIface)

	return ns, nil

}

// Cleanup removes all iptables rules and routes created by this setup.
// Safe to call multiple times.
func (ns *NetworkSetup) Cleanup() {
	// Run undo functions in reverse order (LIFO)
	for i := len(ns.undoList) - 1; i >= 0; i-- {
		ns.undoList[i]()
	}
	ns.undoList = nil
}

func (ns *NetworkSetup) addUndo(fn func()) {
	ns.undoList = append(ns.undoList, fn)
}

// runCmd runs a system command and returns any error (stdout+stderr included).
func runCmd(name string, args ...string) error {
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s %v failed: %w: %s",
			name, args, err, strings.TrimSpace(string(out)))
	}
	debugf("$ %s %v", name, args)
	return nil
}

// ipt runs an iptables command, swallowing "not found" errors on -D operations
// (rule may already be gone if cleanup runs twice).
func ipt(args ...string) error {
	out, err := exec.Command("iptables", args...).CombinedOutput()
	if err != nil {
		msg := strings.TrimSpace(string(out))
		// Ignore "does not exist" errors during cleanup (-D operations)
		if strings.Contains(msg, "No chain/target/match") ||
			strings.Contains(msg, "does not exist") {
			return nil
		}
		return fmt.Errorf("iptables %v: %w: %s", args, err, msg)
	}
	debugf("$ iptables %v", args)
	return nil
}
// ip6t runs an ip6tables command with the same error-swallowing logic as ipt.
func ip6t(args ...string) error {
	out, err := exec.Command("ip6tables", args...).CombinedOutput()
	if err != nil {
		msg := strings.TrimSpace(string(out))
		if strings.Contains(msg, "No chain/target/match") ||
			strings.Contains(msg, "does not exist") {
			return nil
		}
		return fmt.Errorf("ip6tables %v: %w: %s", args, err, msg)
	}
	debugf("$ ip6tables %v", args)
	return nil
}

// detectDefaultInterface reads /proc/net/route and returns the interface
// associated with the default route (destination=0.0.0.0, mask=0.0.0.0).
func detectDefaultInterface() (string, error) {
	f, err := os.Open("/proc/net/route")
	if err != nil {
		return "", err
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	scanner.Scan() // skip header: "Iface Destination Gateway ..."

	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 8 {
			continue
		}
		// Column 1: Destination (hex), Column 7: Mask (hex)
		// Default route: both are "00000000"
		if fields[1] == "00000000" && fields[7] == "00000000" {
			return fields[0], nil
		}
	}

	// Fallback: first non-loopback UP interface with an IPv4 address
	ifaces, err := net.Interfaces()
	if err != nil {
		return "", err
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagLoopback != 0 || iface.Flags&net.FlagUp == 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, addr := range addrs {
			if ipNet, ok := addr.(*net.IPNet); ok && ipNet.IP.To4() != nil {
				return iface.Name, nil
			}
		}
	}

	return "", fmt.Errorf("no default route found in /proc/net/route")
}