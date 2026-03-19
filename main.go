package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/pyd-07/opentether/relay"
)

func main() {
	cfg := relay.Config{
		ListenAddr:      "127.0.0.1:8765",
		TunName:         "ot0",
		TunAddr:         "10.0.0.2",
		TunPrefix:       24,
		AndroidIP:       "10.0.0.1",
		MTU:             1500,
		DisableAdbWatch: false,
	}

	flag.StringVar(&cfg.ListenAddr, "listen", cfg.ListenAddr,
		"TCP address to listen on (must be 127.0.0.1:PORT)")
	flag.StringVar(&cfg.TunName, "tun", cfg.TunName,
		"TUN interface name to create")
	flag.StringVar(&cfg.TunAddr, "tun-addr", cfg.TunAddr,
		"IP address for the relay side of the TUN")
	flag.IntVar(&cfg.TunPrefix, "tun-prefix", cfg.TunPrefix,
		"Prefix length for tun-addr (e.g. 24 for /24)")
	flag.StringVar(&cfg.AndroidIP, "android-ip", cfg.AndroidIP,
		"IP address assigned to the Android VPN client")
	flag.StringVar(&cfg.OutIface, "out-iface", "",
		"Outbound interface for NAT (empty = auto-detect)")
	flag.IntVar(&cfg.MTU, "mtu", cfg.MTU,
		"MTU for TUN interface and packet buffers")
	flag.BoolVar(&cfg.DisableAdbWatch, "no-adb-watch", cfg.DisableAdbWatch,
		"Disable automatic `adb reverse` setup (run it manually instead)")
	flag.BoolVar(&cfg.Verbose, "v", false,
		"Enable per-packet debug logging")
	flag.Parse()

	srv, err := relay.NewServer(cfg)
	if err != nil {
		log.Fatalf("startup failed: %v", err)
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		s := <-sig
		log.Printf("received %v — shutting down", s)
		srv.Stop()
	}()

	if err := srv.Run(); err != nil {
		log.Fatalf("server error: %v", err)
	}
}