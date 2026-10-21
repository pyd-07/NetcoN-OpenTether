package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/pyd-07/opentether/internal/aoa"
	"github.com/pyd-07/opentether/internal/config"
	"github.com/pyd-07/opentether/internal/relay"
)

func main() {
	cfg := config.Config{
		ListenAddr:      "127.0.0.1:8765",
		TunName:         "ot0",
		TunAddr:         "10.0.0.2",
		TunPrefix:       24,
		AndroidIP:       "10.0.0.1",
		MTU:             1400,
		DisableAdbWatch: false,
		AdbBin:          "adb",
		AdbPort:         0,
	}

	flag.StringVar(&cfg.ListenAddr, "listen", cfg.ListenAddr,
		"Loopback TCP address to listen on (stock Android client uses 127.0.0.1:8765; other loopback literals require a matching client/reverse setup)")
	flag.StringVar(&cfg.TunName, "tun", cfg.TunName,
		"TUN interface name to create")
	flag.StringVar(&cfg.TunAddr, "tun-addr", cfg.TunAddr,
		"IP address for the relay side of the TUN (stock client pairs it with --android-ip 10.0.0.1)")
	flag.IntVar(&cfg.TunPrefix, "tun-prefix", cfg.TunPrefix,
		"Prefix length for tun-addr (stock client uses /24)")
	flag.StringVar(&cfg.AndroidIP, "android-ip", cfg.AndroidIP,
		"IP address assigned to the Android VPN client (stock client hard-codes 10.0.0.1)")
	flag.StringVar(&cfg.OutIface, "out-iface", "",
		"Outbound interface for NAT (empty = auto-detect)")
	flag.IntVar(&cfg.MTU, "mtu", cfg.MTU,
		"MTU for TUN interface and packet buffers (1280..1400; stock Android client uses 1400)")
	flag.BoolVar(&cfg.DisableAdbWatch, "no-adb-watch", cfg.DisableAdbWatch,
		"Disable automatic `adb reverse` setup (run it manually instead)")
	flag.StringVar(&cfg.AdbBin, "adb-bin", cfg.AdbBin,
		"ADB executable used for automatic `adb reverse` setup (default \"adb\" from PATH; point at a full path such as the Windows adb.exe when invoking from WSL)")
	flag.IntVar(&cfg.AdbPort, "adb-port", cfg.AdbPort,
		"ADB server port for automatic `adb reverse` setup (0 = the ADB client's default; e.g. 5038 for a non-default Windows ADB server)")
	flag.BoolVar(&cfg.Verbose, "v", false,
		"Enable per-packet debug logging")
	aoaMode := flag.Bool("aoa", false,
		"Use USB Accessory (AOA) mode — no adb or USB debugging required.\n"+
			"Build with -tags aoa and install libusb: apt install libusb-1.0-0-dev")
	flag.Parse()

	// ── AOA mode ──────────────────────────────────────────────────────────
	// Bypasses the TCP listener entirely. The relay negotiates directly with
	// the Android device over USB bulk endpoints.
	if *aoaMode {
		stop := make(chan struct{})
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		go func() {
			s := <-sig
			log.Printf("received %v — shutting down", s)
			close(stop)
		}()

		if err := aoa.RunAOA(cfg, stop); err != nil {
			log.Fatalf("AOA error: %v", err)
		}
		return
	}

	// ── TCP mode (default) ────────────────────────────────────────────────
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
