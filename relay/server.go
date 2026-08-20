package relay

import (
	"context"
	"fmt"
	"net"
	"sync/atomic"
)

// Server owns the TUN device, TCP listener, ADB watcher, and manages the
// lifecycle of Android client sessions.
type Server struct {
	cfg      Config
	tun      *TunDevice
	netSetup *NetworkSetup
	listener net.Listener
	stopped  atomic.Bool
	ctx      context.Context
	cancel   context.CancelFunc
	state    *ConnectionStateTracker
}

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
		state:    NewConnectionStateTracker(),
	}, nil
}

func (s *Server) Run() error {
	defer s.cleanup()

	if !s.cfg.DisableAdbWatch {
		port := listenPort(s.cfg.ListenAddr)
		watcher := NewAdbWatcher(port)
		go watcher.Watch(s.ctx)
		logf("ADB watcher started — automatic device detection and reconnect enabled")
	}

	s.state.Set(StateDisconnected)
	logf("ready — waiting for Android on %s", s.cfg.ListenAddr)

	for {
		conn, err := s.listener.Accept()
		if s.stopped.Load() {
			return nil
		}
		if err != nil {
			if s.ctx.Err() != nil {
				return nil
			}
			s.state.Fail(fmt.Errorf("accept: %w", err))
			errorf("accept: %v", err)
			continue
		}

		if tc, ok := conn.(*net.TCPConn); ok {
			if err := tc.SetKeepAlive(true); err != nil {
				debugf("TCP keepalive setup for %s failed: %v", conn.RemoteAddr(), err)
			}
		}

		s.state.Set(StateConnected)
		logf("Android connected from %s — session starting", conn.RemoteAddr())
		sess := newSession(conn, s.tun, s.cfg)
		sess.run(s.ctx)
		s.state.Set(StateDisconnected)
		logf("Android session ended — waiting for reconnect")
	}
}

func (s *Server) Stop() {
	if s.stopped.Swap(true) {
		return
	}
	s.state.Set(StateStopping)
	s.cancel()
	if err := s.listener.Close(); err != nil {
		debugf("listener close: %v", err)
	}
}

func (s *Server) cleanup() {
	s.state.Set(StateStopping)
	logf("removing iptables rules and routes...")
	s.netSetup.Cleanup()
	if err := s.tun.Close(); err != nil {
		debugf("TUN close: %v", err)
	}
	s.state.Set(StateDisconnected)
	logf("shutdown complete")
}

func listenPort(addr string) int {
	_, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		warnf("listenPort: could not parse %q: %v — defaulting to 8765", addr, err)
		return 8765
	}
	var port int
	if _, err := fmt.Sscanf(portStr, "%d", &port); err != nil || port <= 0 || port > 65535 {
		warnf("listenPort: invalid port %q — defaulting to 8765", portStr)
		return 8765
	}
	return port
}
