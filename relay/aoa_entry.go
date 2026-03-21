//go:build linux && aoa

package relay

import (
	"context"
)

// RunAOA sets up the TUN device and network rules (identical to NewServer),
// then hands off to AoaServer instead of the TCP listener.
//
// AOAMode is set to true automatically — this switches session writes to
// DirectWriter, which sends each OTP frame as exactly one USB bulk transfer.
// This prevents the payload_length corruption that occurs when Android's USB
// accessory driver returns one USB transfer per read() without buffering the
// remainder of a multi-frame transfer.
//
// stop is closed by the caller (main) when SIGINT/SIGTERM arrives.
// RunAOA blocks until stop is closed or the USB context exits.
func RunAOA(cfg Config, stop <-chan struct{}) error {
	cfg.AOAMode = true // must be set before any session is created
	setVerbose(cfg.Verbose)

	tun, err := OpenTUN(cfg.TunName)
	if err != nil {
		return err
	}
	logf("TUN device created: /dev/net/tun → %s", tun.Name())

	netSetup, err := NewNetworkSetup(cfg, tun)
	if err != nil {
		tun.Close()
		return err
	}
	defer func() {
		logf("AOA: removing iptables rules and routes...")
		netSetup.Cleanup()
		tun.Close()
		logf("AOA: shutdown complete")
	}()

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		<-stop
		cancel()
	}()

	NewAoaServer(cfg, tun).Run(ctx)
	return nil
}
