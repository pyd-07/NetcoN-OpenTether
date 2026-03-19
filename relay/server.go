package relay

import (
	"context"
	"fmt"
	"net"
	"sync/atomic"
)

// Server owns the TUN device, TCP listener, and ADB watcher, and manages the
// lifecycle of Android client sessions.
type Server struct {
	cfg      Config
	tun      *TunDevice
	netSetup *NetworkSetup
	listener net.Listener
	stopped  atomic.Bool
	ctx      context.Context
	cancel   context.CancelFunc
}

// NewServer creates the TUN interface, configures networking, and starts
// the TCP listener. Returns an error if any of these steps fail.
// Must be run as root (or with CAP_NET_ADMIN + CAP_NET_RAW).
func NewServer(cfg Config) (*Server, error) {
	setVerbose(cfg.Verbose)

	tun, err := OpenTUN(cfg.TunName)
	if err != nil {
		return nil, err
	}
	logf("TUN device created: /dev/net/tun → %s", tun.Name())

	netSetup, err := NewNetworkSetup(cfg, tun)
	if err != nil {
		tun.Close()
		return nil, err
	}

	ln, err := net.Listen("tcp", cfg.ListenAddr)
	if err != nil {
		netSetup.Cleanup()
		tun.Close()
		return nil, fmt.Errorf("listen %s: %w", cfg.ListenAddr, err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	return &Server{
		cfg:      cfg,
		tun:      tun,
		netSetup: netSetup,
		listener: ln,
		ctx:      ctx,
		cancel:   cancel,
	}, nil
}

// Run accepts Android client connections and handles reconnections automatically.
//
// If cfg.AutoAdb is true (the default when adb is on PATH), an AdbWatcher is
// started in the background. It polls `adb devices` every 2 s and runs
// `adb reverse tcp:PORT tcp:PORT` automatically whenever a device appears —
// no manual setup step needed.
//
// Blocks until Stop() is called.
func (s *Server) Run() error {
	defer s.cleanup()

	// ── Auto ADB tunnel ──────────────────────────────────────────────────
	if !s.cfg.DisableAdbWatch {
		port := listenPort(s.cfg.ListenAddr)
		watcher := NewAdbWatcher(port)
		go watcher.Watch(s.ctx)
		logf("ADB watcher started — will configure `adb reverse tcp:%d tcp:%d` automatically", port, port)
	}

	logf("ready — waiting for Android on %s", s.cfg.ListenAddr)

	for {
		conn, err := s.listener.Accept()
		if s.stopped.Load() {
			return nil
		}
		if err != nil {
			errorf("accept: %v", err)
			continue
		}

		if tc, ok := conn.(*net.TCPConn); ok {
			tc.SetKeepAlive(true)
		}

		logf("Android connected from %s", conn.RemoteAddr())
		sess := newSession(conn, s.tun, s.cfg)
		sess.run(s.ctx)
		logf("session ended — waiting for reconnect")
	}
}

// Stop shuts down the server, closes the listener, and cleans up networking.
func (s *Server) Stop() {
	s.stopped.Store(true)
	s.cancel()
	s.listener.Close()
}

func (s *Server) cleanup() {
	logf("removing iptables rules and routes...")
	s.netSetup.Cleanup()
	s.tun.Close()
	logf("shutdown complete")
}

// listenPort parses the port number from a "host:port" address string.
// Falls back to 8765 on any parse error.
func listenPort(addr string) int {
	var host string
	var port int
	if _, err := fmt.Sscanf(addr, "%s", &host); err != nil {
		return 8765
	}
	// addr is "host:port"
	for i := len(addr) - 1; i >= 0; i-- {
		if addr[i] == ':' {
			fmt.Sscanf(addr[i+1:], "%d", &port)
			break
		}
	}
	if port == 0 {
		return 8765
	}
	return port
}