//go:build linux

package relay

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
)

// commandRunner executes one host-networking command and returns its combined
// output. It is injectable so setup/rollback can be tested without root.
type commandRunner func(name string, args ...string) ([]byte, error)

// sysctlReader and sysctlWriter are injectable procfs seams.
type sysctlReader func(path string) (string, error)
type sysctlWriter func(path, value string) error

// NetworkSetup configures the TUN interface and iptables rules.
// Call Cleanup() on shutdown to remove all rules added by this setup.
type NetworkSetup struct {
	cfg      Config
	OutIface string // resolved outbound interface name

	run         commandRunner
	readSysctl  sysctlReader
	writeSysctl sysctlWriter

	mu         sync.Mutex
	undoList   []func() error
	cleanupErr error
}

// NewNetworkSetup configures the OS for relaying:
//  1. Assigns IP to TUN interface
//  2. Brings TUN up
//  3. Adds route for Android IP via TUN
//  4. Enables IPv4 forwarding
//  5. Adds MASQUERADE iptables rule
//
// Every successful mutation is recorded for rollback. If any step fails, the
// resources created so far are removed before the error is returned.
func NewNetworkSetup(cfg Config, tun *TunDevice) (*NetworkSetup, error) {
	return newNetworkSetup(runHostCommand, readSysctlFile, writeSysctlFile, cfg, tun)
}

func newNetworkSetup(
	run commandRunner,
	readSysctl sysctlReader,
	writeSysctl sysctlWriter,
	cfg Config,
	tun *TunDevice,
) (*NetworkSetup, error) {
	outIface := cfg.OutIface
	if outIface == "" {
		var err error
		outIface, err = detectDefaultInterface()
		if err != nil {
			return nil, fmt.Errorf("detect default interface: %w", err)
		}
	}

	ns := &NetworkSetup{
		cfg:         cfg,
		OutIface:    outIface,
		run:         run,
		readSysctl:  readSysctl,
		writeSysctl: writeSysctl,
	}
	logf("outbound interface: %s", outIface)

	if err := ns.configure(tun); err != nil {
		rbErr := ns.CleanupErr()
		if rbErr != nil {
			return nil, fmt.Errorf("%w (rollback incomplete: %v)", err, rbErr)
		}
		return nil, err
	}
	return ns, nil
}

func (ns *NetworkSetup) configure(tun *TunDevice) error {
	tunName := tun.Name()
	cidr := fmt.Sprintf("%s/%d", ns.cfg.TunAddr, ns.cfg.TunPrefix)
	androidCIDR := ns.cfg.AndroidIP + "/32"

	// Assign IP address to TUN. The interface is exclusively owned by this
	// process (IFF_TUN_EXCL), so replacing its address is safe.
	if err := ns.step(
		func() error {
			return ignoreMissing(ns.run("ip", "addr", "del", cidr, "dev", tunName))
		},
		"ip", "addr", "replace", cidr, "dev", tunName,
	); err != nil {
		return fmt.Errorf("ip addr: %w", err)
	}

	// Set MTU
	if err := ns.runOnly("ip", "link", "set", "dev", tunName, "mtu", strconv.Itoa(ns.cfg.MTU)); err != nil {
		return fmt.Errorf("set mtu: %w", err)
	}

	// Bring interface up
	if err := ns.step(
		func() error {
			return ignoreMissing(ns.run("ip", "link", "set", "dev", tunName, "down"))
		},
		"ip", "link", "set", "dev", tunName, "up",
	); err != nil {
		return fmt.Errorf("ip link up: %w", err)
	}

	// Add route for Android IP through TUN (replace = idempotent)
	if err := ns.step(
		func() error {
			return ignoreMissing(ns.run("ip", "route", "del", androidCIDR, "dev", tunName))
		},
		"ip", "route", "replace", androidCIDR, "dev", tunName,
	); err != nil {
		return fmt.Errorf("ip route: %w", err)
	}

	// Enable IPv4 forwarding, restoring the previous value if this run
	// changed it. A host that already had forwarding enabled is left alone.
	if err := ns.enableForwarding("/proc/sys/net/ipv4/ip_forward", "IPv4"); err != nil {
		return err
	}

	// Clamp MSS only for flows to or from the Android tunnel address. Applying
	// this host-wide would silently alter forwarded traffic belonging to other
	// services (Docker, VMs, other VPNs).
	for _, direction := range []string{"-s", "-d"} {
		args := []string{
			"-t", "mangle", "-A", "FORWARD",
			direction, androidCIDR,
			"-p", "tcp", "--tcp-flags", "SYN,RST", "SYN",
			"-j", "TCPMSS", "--clamp-mss-to-pmtu",
		}
		if err := ns.addRule("iptables", args...); err != nil {
			return fmt.Errorf("iptables MSS clamp (%s): %w", direction, err)
		}
	}

	// iptables MASQUERADE — all traffic from Android gets SNAT to PC's real IP
	masquerade4 := []string{
		"-t", "nat", "-A", "POSTROUTING",
		"-s", androidCIDR, "-o", ns.OutIface, "-j", "MASQUERADE",
	}
	if err := ns.addRule("iptables", masquerade4...); err != nil {
		return fmt.Errorf("iptables MASQUERADE: %w", err)
	}

	// Allow forwarding through TUN in both directions
	if err := ns.addRule("iptables", "-A", "FORWARD", "-i", tunName, "-j", "ACCEPT"); err != nil {
		return fmt.Errorf("iptables FORWARD in: %w", err)
	}
	if err := ns.addRule("iptables", "-A", "FORWARD", "-o", tunName, "-j", "ACCEPT"); err != nil {
		return fmt.Errorf("iptables FORWARD out: %w", err)
	}

	// ── IPv6 forwarding + NAT ─────────────────────────────────────────────
	// The Android VPN client captures all IPv6 traffic (addRoute("::", 0)).
	// Without ip6tables MASQUERADE those packets arrive on the TUN and die.

	// Assign an IPv6 address to the TUN interface.
	// We use a unique-local (fc00::/7) address — no ISP allocation needed.
	tun6CIDR := "fdcc::2/64"
	android6 := "fdcc::1"
	android6CIDR := android6 + "/128"

	if err := ns.step(
		func() error {
			return ignoreMissing(ns.run("ip", "-6", "addr", "del", tun6CIDR, "dev", tunName))
		},
		"ip", "-6", "addr", "replace", tun6CIDR, "dev", tunName,
	); err != nil {
		return fmt.Errorf("ip -6 addr: %w", err)
	}

	if err := ns.step(
		func() error {
			return ignoreMissing(ns.run("ip", "-6", "route", "del", android6CIDR, "dev", tunName))
		},
		"ip", "-6", "route", "replace", android6CIDR, "dev", tunName,
	); err != nil {
		return fmt.Errorf("ip -6 route: %w", err)
	}

	if err := ns.enableForwarding("/proc/sys/net/ipv6/conf/all/forwarding", "IPv6"); err != nil {
		return err
	}

	masquerade6 := []string{
		"-t", "nat", "-A", "POSTROUTING",
		"-s", android6CIDR, "-o", ns.OutIface, "-j", "MASQUERADE",
	}
	if err := ns.addRule("ip6tables", masquerade6...); err != nil {
		return fmt.Errorf("ip6tables MASQUERADE: %w", err)
	}
	if err := ns.addRule("ip6tables", "-A", "FORWARD", "-i", tunName, "-j", "ACCEPT"); err != nil {
		return fmt.Errorf("ip6tables FORWARD in: %w", err)
	}
	if err := ns.addRule("ip6tables", "-A", "FORWARD", "-o", tunName, "-j", "ACCEPT"); err != nil {
		return fmt.Errorf("ip6tables FORWARD out: %w", err)
	}

	logf("IPv6 NAT configured: TUN=%s android=%s outbound=%s", tun6CIDR, android6, ns.OutIface)
	return nil
}

// step runs one command and records its inverse only when the command
// succeeded.
func (ns *NetworkSetup) step(undo func() error, name string, args ...string) error {
	if err := ns.runOnly(name, args...); err != nil {
		return err
	}
	if undo != nil {
		ns.addUndo(undo)
	}
	return nil
}

func (ns *NetworkSetup) runOnly(name string, args ...string) error {
	_, err := ns.run(name, args...)
	return err
}

// addRule appends a firewall rule only when an exact equivalent is not already
// present. A pre-existing rule is left in place and is not recorded for
// cleanup, so OpenTether never removes a rule it did not create.
func (ns *NetworkSetup) addRule(binary string, args ...string) error {
	check := replaceRuleOp(args, "-A", "-C")
	if _, err := ns.run(binary, check...); err == nil {
		debugf("%s rule already present; leaving it in place: %v", binary, args)
		return nil
	}

	if _, err := ns.run(binary, args...); err != nil {
		return err
	}
	deleteArgs := replaceRuleOp(args, "-A", "-D")
	ns.addUndo(func() error {
		return ignoreMissing(ns.run(binary, deleteArgs...))
	})
	return nil
}

// replaceRuleOp replaces the first occurrence of from with to. It is used to
// derive -C and -D variants of an -A rule.
func replaceRuleOp(args []string, from, to string) []string {
	out := append([]string(nil), args...)
	for i, arg := range out {
		if arg == from {
			out[i] = to
			break
		}
	}
	return out
}

// enableForwarding enables a forwarding sysctl only when it is currently off,
// and restores the observed value on cleanup. If forwarding was already on,
// OpenTether does not own it and leaves it untouched.
func (ns *NetworkSetup) enableForwarding(path, label string) error {
	previous, err := ns.readSysctl(path)
	if err != nil {
		return fmt.Errorf("read %s forwarding: %w", label, err)
	}
	if strings.TrimSpace(previous) == "1" {
		debugf("%s forwarding already enabled; leaving it unchanged", label)
		return nil
	}

	if err := ns.writeSysctl(path, "1\n"); err != nil {
		return fmt.Errorf("enable %s forwarding: %w", label, err)
	}

	restore := previous
	if !strings.HasSuffix(restore, "\n") {
		restore += "\n"
	}
	ns.addUndo(func() error {
		return ns.writeSysctl(path, restore)
	})
	return nil
}

// Cleanup removes all iptables rules, routes, and interface state created by
// this setup. Safe to call multiple times.
func (ns *NetworkSetup) Cleanup() {
	if err := ns.CleanupErr(); err != nil {
		warnf("network cleanup incomplete: %v", err)
	}
}

// CleanupErr removes owned resources and reports every failed undo. The undo
// list is cleared even if some steps fail, so a second call cannot execute the
// same cleanup command twice.
func (ns *NetworkSetup) CleanupErr() error {
	ns.mu.Lock()
	defer ns.mu.Unlock()

	if len(ns.undoList) == 0 {
		return ns.cleanupErr
	}

	var errs []error
	for i := len(ns.undoList) - 1; i >= 0; i-- {
		if err := ns.undoList[i](); err != nil {
			wrapped := fmt.Errorf("cleanup step %d: %w", i, err)
			warnf("network cleanup step %d failed: %v", i, err)
			errs = append(errs, wrapped)
		}
	}
	ns.undoList = nil
	ns.cleanupErr = errors.Join(errs...)
	return ns.cleanupErr
}

func (ns *NetworkSetup) addUndo(fn func() error) {
	ns.mu.Lock()
	ns.undoList = append(ns.undoList, fn)
	ns.mu.Unlock()
}

// runHostCommand runs a host command and returns any error (stdout+stderr
// included).
func runHostCommand(name string, args ...string) ([]byte, error) {
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		return out, fmt.Errorf("%s %v failed: %w: %s",
			name, args, err, strings.TrimSpace(string(out)))
	}
	debugf("$ %s %v", name, args)
	return out, nil
}

func readSysctlFile(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func writeSysctlFile(path, value string) error {
	return os.WriteFile(path, []byte(value), 0644)
}

// ignoreMissing treats teardown of an already-absent resource as success. It
// accepts the (output, error) pair returned by a command runner so call sites
// can pass run(...) directly.
func ignoreMissing(_ []byte, err error) error {
	return ignoreMissingDelete(err)
}

// ignoreMissingDelete is the narrower form used for iptables delete/check
// operations. Add failures are never ignored.
func ignoreMissingDelete(err error) error {
	if err == nil {
		return nil
	}
	msg := strings.ToLower(err.Error())
	for _, fragment := range []string{
		"does not exist",
		"no chain/target/match",
		"cannot find device",
		"no such process",
	} {
		if strings.Contains(msg, fragment) {
			return nil
		}
	}
	return err
}

// detectDefaultInterface reads /proc/net/route and returns the interface
// associated with the lowest-metric default route.
func detectDefaultInterface() (string, error) {
	f, err := os.Open("/proc/net/route")
	if err != nil {
		return "", err
	}
	defer f.Close()

	if name, err := parseDefaultInterface(f); err == nil {
		return name, nil
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

// parseDefaultInterface selects the lowest-metric default route from
// /proc/net/route content. A zero metric is valid and wins.
func parseDefaultInterface(r io.Reader) (string, error) {
	scanner := bufio.NewScanner(r)
	scanner.Scan() // skip header: "Iface Destination Gateway ..."

	bestIface := ""
	bestMetric := int(^uint(0) >> 1) // max int
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 8 {
			continue
		}
		// Column 1: Destination (hex), Column 6: Metric, Column 7: Mask (hex)
		if fields[1] != "00000000" || fields[7] != "00000000" {
			continue
		}
		metric, err := strconv.Atoi(fields[6])
		if err != nil {
			metric = 0
		}
		if metric < bestMetric {
			bestMetric = metric
			bestIface = fields[0]
		}
	}
	if bestIface == "" {
		return "", fmt.Errorf("no default route found in /proc/net/route")
	}
	return bestIface, nil
}
