//go:build linux

package relay

import (
	"fmt"
	"strings"
	"sync"
	"testing"
)

type fakeHostRunner struct {
	mu          sync.Mutex
	calls       [][]string
	absentRules bool
	fail        func(name string, args []string) error
}

func (f *fakeHostRunner) run(name string, args ...string) ([]byte, error) {
	f.mu.Lock()
	f.calls = append(f.calls, append([]string{name}, args...))
	f.mu.Unlock()

	if f.fail != nil {
		if err := f.fail(name, args); err != nil {
			return nil, err
		}
	}
	if f.absentRules && containsArg(args, "-C") {
		return []byte("iptables: Bad rule (does a matching rule exist in that chain?)."), fmt.Errorf("exit status 1")
	}
	return nil, nil
}

func (f *fakeHostRunner) snapshot() [][]string {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([][]string, len(f.calls))
	for i := range f.calls {
		out[i] = append([]string(nil), f.calls[i]...)
	}
	return out
}

func (f *fakeHostRunner) hasCall(substr string) bool {
	for _, call := range f.snapshot() {
		if strings.Contains(strings.Join(call, " "), substr) {
			return true
		}
	}
	return false
}

func (f *fakeHostRunner) countCalls(substr string) int {
	count := 0
	for _, call := range f.snapshot() {
		if strings.Contains(strings.Join(call, " "), substr) {
			count++
		}
	}
	return count
}

type fakeSysctl struct {
	mu     sync.Mutex
	values map[string]string
	writes []string
}

func newFakeSysctl() *fakeSysctl {
	return &fakeSysctl{values: map[string]string{
		"/proc/sys/net/ipv4/ip_forward":          "0\n",
		"/proc/sys/net/ipv6/conf/all/forwarding": "0\n",
	}}
}

func (f *fakeSysctl) read(path string) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	value, ok := f.values[path]
	if !ok {
		return "", fmt.Errorf("missing sysctl %s", path)
	}
	return value, nil
}

func (f *fakeSysctl) write(path, value string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.values[path] = value
	f.writes = append(f.writes, path+"="+strings.TrimSpace(value))
	return nil
}

func (f *fakeSysctl) snapshotWrites() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.writes...)
}

func networkTestConfig() Config {
	cfg := testValidConfig()
	cfg.OutIface = "eth0"
	return cfg
}

func TestNetworkSetupRollsBackAfterPartialFailure(t *testing.T) {
	runner := &fakeHostRunner{
		absentRules: true,
		fail: func(name string, args []string) error {
			if name == "iptables" && containsArg(args, "POSTROUTING") && containsArg(args, "-A") {
				return fmt.Errorf("simulated MASQUERADE failure")
			}
			return nil
		},
	}
	sysctl := newFakeSysctl()

	ns, err := newNetworkSetup(runner.run, sysctl.read, sysctl.write, networkTestConfig(), &TunDevice{name: "ot0"})
	if err == nil {
		t.Fatal("expected setup to fail")
	}
	if ns != nil {
		t.Fatal("failed setup returned a non-nil NetworkSetup")
	}

	for _, want := range []string{
		"ip addr del 10.0.0.2/24 dev ot0",
		"ip link set dev ot0 down",
		"ip route del 10.0.0.1/32 dev ot0",
		"iptables -t mangle -D FORWARD",
	} {
		if !runner.hasCall(want) {
			t.Fatalf("rollback missing %q; calls: %v", want, runner.snapshot())
		}
	}
	if writes := sysctl.snapshotWrites(); len(writes) != 2 ||
		writes[0] != "/proc/sys/net/ipv4/ip_forward=1" ||
		writes[1] != "/proc/sys/net/ipv4/ip_forward=0" {
		t.Fatalf("ip_forward not restored during rollback: %v", writes)
	}
	if runner.hasCall("iptables -t nat -D POSTROUTING") {
		t.Fatal("rollback attempted to delete a MASQUERADE rule that was never added")
	}
}

func TestNetworkSetupRollsBackIPv4WhenIPv6SetupFails(t *testing.T) {
	runner := &fakeHostRunner{
		absentRules: true,
		fail: func(name string, args []string) error {
			if name == "ip" && len(args) >= 3 && args[0] == "-6" && args[1] == "addr" && args[2] == "replace" {
				return fmt.Errorf("simulated IPv6 address failure")
			}
			return nil
		},
	}
	sysctl := newFakeSysctl()

	ns, err := newNetworkSetup(runner.run, sysctl.read, sysctl.write, networkTestConfig(), &TunDevice{name: "ot0"})
	if err == nil {
		t.Fatal("expected setup to fail")
	}
	if ns != nil {
		t.Fatal("failed setup returned a non-nil NetworkSetup")
	}

	for _, want := range []string{
		"iptables -t nat -D POSTROUTING",
		"iptables -D FORWARD -i ot0",
		"iptables -D FORWARD -o ot0",
		"ip -6 addr del",
	} {
		present := runner.hasCall(want)
		if want == "ip -6 addr del" {
			if present {
				t.Fatal("rollback deleted an IPv6 address that was never added")
			}
			continue
		}
		if !present {
			t.Fatalf("IPv4 rollback missing %q; calls: %v", want, runner.snapshot())
		}
	}
}

func TestNetworkSetupLeavesPreexistingRulesAndDoesNotRemoveThem(t *testing.T) {
	runner := &fakeHostRunner{absentRules: false} // every -C succeeds
	sysctl := newFakeSysctl()

	ns, err := newNetworkSetup(runner.run, sysctl.read, sysctl.write, networkTestConfig(), &TunDevice{name: "ot0"})
	if err != nil {
		t.Fatalf("setup failed: %v", err)
	}
	if ns == nil {
		t.Fatal("setup returned nil")
	}

	if got := runner.countCalls("-A"); got != 0 {
		t.Fatalf("added %d firewall rules even though all checks succeeded: %v", got, runner.snapshot())
	}

	ns.Cleanup()
	if got := runner.countCalls("-D"); got != 0 {
		t.Fatalf("cleanup deleted %d pre-existing/firewall rules it did not add: %v", got, runner.snapshot())
	}
}

func TestNetworkSetupPropagatesFirewallAddFailure(t *testing.T) {
	runner := &fakeHostRunner{
		absentRules: true,
		fail: func(name string, args []string) error {
			if name == "iptables" && containsArg(args, "-A") && containsArg(args, "TCPMSS") {
				return fmt.Errorf("iptables: No chain/target/match by that name")
			}
			return nil
		},
	}
	sysctl := newFakeSysctl()

	ns, err := newNetworkSetup(runner.run, sysctl.read, sysctl.write, networkTestConfig(), &TunDevice{name: "ot0"})
	if err == nil {
		t.Fatal("a firewall add failure must not be swallowed")
	}
	if ns != nil {
		t.Fatal("failed setup returned a non-nil NetworkSetup")
	}
	if !strings.Contains(err.Error(), "MSS clamp") {
		t.Fatalf("error = %v, want MSS clamp context", err)
	}
}

func TestNetworkSetupRestoresForwardingOnlyWhenChanged(t *testing.T) {
	t.Run("off -> on -> restored", func(t *testing.T) {
		sysctl := newFakeSysctl()
		ns := &NetworkSetup{readSysctl: sysctl.read, writeSysctl: sysctl.write}

		if err := ns.enableForwarding("/proc/sys/net/ipv4/ip_forward", "IPv4"); err != nil {
			t.Fatalf("enableForwarding: %v", err)
		}
		if err := ns.CleanupErr(); err != nil {
			t.Fatalf("cleanup: %v", err)
		}

		writes := sysctl.snapshotWrites()
		want := []string{
			"/proc/sys/net/ipv4/ip_forward=1",
			"/proc/sys/net/ipv4/ip_forward=0",
		}
		if len(writes) != len(want) {
			t.Fatalf("writes = %v, want %v", writes, want)
		}
		for i := range want {
			if writes[i] != want[i] {
				t.Fatalf("writes = %v, want %v", writes, want)
			}
		}
	})

	t.Run("already on -> untouched", func(t *testing.T) {
		sysctl := newFakeSysctl()
		sysctl.values["/proc/sys/net/ipv4/ip_forward"] = "1\n"
		ns := &NetworkSetup{readSysctl: sysctl.read, writeSysctl: sysctl.write}

		if err := ns.enableForwarding("/proc/sys/net/ipv4/ip_forward", "IPv4"); err != nil {
			t.Fatalf("enableForwarding: %v", err)
		}
		if err := ns.CleanupErr(); err != nil {
			t.Fatalf("cleanup: %v", err)
		}
		if writes := sysctl.snapshotWrites(); len(writes) != 0 {
			t.Fatalf("writes = %v, want none for pre-existing forwarding", writes)
		}
	})
}

func TestNetworkCleanupReportsIncompleteTeardown(t *testing.T) {
	ns := &NetworkSetup{}
	ns.addUndo(func() error { return fmt.Errorf("cannot remove route") })
	ns.addUndo(func() error { return nil })

	if err := ns.CleanupErr(); err == nil {
		t.Fatal("cleanup did not report the failed undo")
	}
	// A second call must not re-run undo steps or lose the recorded failure.
	if err := ns.CleanupErr(); err == nil {
		t.Fatal("second cleanup lost the recorded failure")
	}
}

func TestParseDefaultInterfaceSelectsLowestMetric(t *testing.T) {
	content := "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n" +
		"eth0\t00000000\t0100000A\t0003\t0\t0\t100\t00000000\t0\t0\t0\n" +
		"wlan0\t00000000\t0100000A\t0003\t0\t0\t50\t00000000\t0\t0\t0\n" +
		"eth1\t00000000\t0100000A\t0003\t0\t0\t200\t00000000\t0\t0\t0\n"

	got, err := parseDefaultInterface(strings.NewReader(content))
	if err != nil {
		t.Fatalf("parseDefaultInterface: %v", err)
	}
	if got != "wlan0" {
		t.Fatalf("interface = %q, want wlan0", got)
	}

	if _, err := parseDefaultInterface(strings.NewReader("Iface\tDestination\n")); err == nil {
		t.Fatal("expected an error when no default route exists")
	}
}

func TestTunIFFlagsAreExclusive(t *testing.T) {
	flags := tunIFFlags()
	if flags&iffTUN == 0 || flags&iffNOPI == 0 {
		t.Fatalf("flags = %#x, want TUN and NOPI set", flags)
	}
	if flags&iffTUNEXCL == 0 {
		t.Fatalf("flags = %#x, want IFF_TUN_EXCL set", flags)
	}
}
