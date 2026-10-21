//go:build linux && aoa

package aoa

import (
	"context"
	"fmt"

	"github.com/pyd-07/opentether/internal/config"
	"github.com/pyd-07/opentether/internal/logging"
	"github.com/pyd-07/opentether/internal/network"
	"github.com/pyd-07/opentether/internal/relay"
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
func RunAOA(cfg config.Config, stop <-chan struct{}) error {
	cfg.AOAMode = true // must be set before any session is created
	logging.SetVerbose(cfg.Verbose)
	if err := cfg.Validate(); err != nil {
		return fmt.Errorf("invalid config: %w", err)
	}

	tun, err := relay.OpenTUN(cfg.TunName)
	if err != nil {
		return err
	}
	logging.Logf("TUN device created: /dev/net/tun → %s", tun.Name())

	netSetup, err := network.NewNetworkSetup(cfg, tun.Name())
	if err != nil {
		tun.Close()
		return err
	}
	defer func() {
		logging.Logf("AOA: removing iptables rules and routes...")
		netSetup.Cleanup()
		tun.Close()
		logging.Logf("AOA: shutdown complete")
	}()

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		<-stop
		cancel()
	}()

	NewAoaServer(cfg, tun).Run(ctx)
	return nil
}
