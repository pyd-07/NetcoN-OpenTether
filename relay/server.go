package relay

import (
	"context"
	"net"
	"sync/atomic"
	"fmt"
)

// Server owns the TUN device and TCP listener, and manages the lifecycle
// of Android client sessions.
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

	// Create TUN device
	tun, err := OpenTUN(cfg.TunName)
	if err != nil {
		return nil, err
	}
	logf("TUN device created: /dev/net/tun → %s", tun.Name())

	// Configure IP address, routes, and iptables
	netSetup, err := NewNetworkSetup(cfg, tun)
	if err != nil {
		tun.Close()
		return nil, err
	}

	// Bind TCP listener — localhost only, security-critical
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

// Run accepts Android client connections. Handles reconnections automatically
// (e.g. cable unplug and replug). Blocks until Stop() is called.
func (s *Server) Run() error {
	defer s.cleanup()

	logf("ready — waiting for Android on %s", s.cfg.ListenAddr)
	logf("run on Android: adb reverse tcp:8765 tcp:8765")

	for {
		conn, err := s.listener.Accept()
		if s.stopped.Load() {
			return nil
		}
		if err != nil {
			errorf("accept: %v", err)
			continue
		}

		// Set TCP keepalive so we detect stale connections
		if tc, ok := conn.(*net.TCPConn); ok {
			tc.SetKeepAlive(true)
		}

		logf("Android connected from %s", conn.RemoteAddr())
		sess := newSession(conn, s.tun, s.cfg)
		sess.run(s.ctx) // blocks until this session ends
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