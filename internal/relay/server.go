package relay

import (
	"context"
	"fmt"
	"net"
	"sync"
	"sync/atomic"

	"github.com/pyd-07/opentether/internal/adb"
	"github.com/pyd-07/opentether/internal/config"
	"github.com/pyd-07/opentether/internal/connection"
	"github.com/pyd-07/opentether/internal/logging"
	"github.com/pyd-07/opentether/internal/network"
)

// Server owns the TUN device, TCP listener, ADB watcher, and manages the
// lifecycle of Android client sessions.
type Server struct {
	cfg      config.Config
	tun      *TunDevice
	netSetup *network.NetworkSetup
	listener net.Listener
	stopped  atomic.Bool
	ctx      context.Context
	cancel   context.CancelFunc
	state    *connection.ConnectionStateTracker

	// adbWG tracks the ADB watcher so shutdown can wait for its reverse-mapping
	// cleanup before the process exits.
	adbWG sync.WaitGroup
}

func NewServer(cfg config.Config) (*Server, error) {
	if cfg.AOAMode {
		return nil, fmt.Errorf("invalid config: NewServer is TCP-only; use RunAOA for AOA mode")
	}

	logging.SetVerbose(cfg.Verbose)
	if err := cfg.Validate(); err != nil {
		return nil, fmt.Errorf("invalid config: %w", err)
	}

	tun, err := OpenTUN(cfg.TunName)
	if err != nil {
		return nil, err
	}
	logging.Logf("TUN device created: /dev/net/tun → %s", tun.Name())

	netSetup, err := network.NewNetworkSetup(cfg, tun.Name())
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
		state:    connection.NewConnectionStateTracker(),
	}, nil
}

func (s *Server) Run() error {
	defer s.cleanup()
	// Wait for the watcher (and any in-flight reverse setup) before network
	// cleanup. Defers run LIFO, so this executes before cleanup.
	defer s.adbWG.Wait()

	if !s.cfg.DisableAdbWatch {
		port := listenPort(s.cfg.ListenAddr)
		watcher := adb.NewAdbWatcher(port, s.cfg.AdbBin, s.cfg.AdbPort)
		s.adbWG.Add(1)
		go func() {
			defer s.adbWG.Done()
			watcher.Watch(s.ctx)
		}()
		logging.Logf("ADB watcher started — automatic device detection and reconnect enabled")
	}

	s.state.Set(connection.StateDisconnected)
	logging.Logf("ready — waiting for Android on %s", s.cfg.ListenAddr)
	return s.serve()
}

// serve accepts Android connections and owns at most one live session. A new
// connection replaces an existing one: the Android client opens a new TCP
// connection only after its previous transport failed, so a half-open or
// wedged session must not block reconnection forever.
func (s *Server) serve() error {
	var active *Session
	var activeDone chan struct{}

	defer func() {
		if active != nil && activeDone != nil {
			active.conn.Close()
			<-activeDone
		}
	}()

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
			logging.Errorf("accept: %v", err)
			continue
		}

		if tc, ok := conn.(*net.TCPConn); ok {
			if err := tc.SetKeepAlive(true); err != nil {
				logging.Debugf("TCP keepalive setup for %s failed: %v", conn.RemoteAddr(), err)
			}
		}

		if active != nil {
			logging.Warnf("new Android connection from %s — replacing previous session from %s",
				conn.RemoteAddr(), active.conn.RemoteAddr())
			active.conn.Close()
			<-activeDone
			active = nil
			activeDone = nil
		}

		s.state.Set(connection.StateConnected)
		logging.Logf("Android connected from %s — session starting", conn.RemoteAddr())

		sess := NewSession(conn, s.tun, s.cfg)
		done := make(chan struct{})
		active = sess
		activeDone = done
		go func() {
			defer close(done)
			sess.Run(s.ctx)
			s.state.Set(connection.StateDisconnected)
			logging.Logf("Android session ended — waiting for reconnect")
		}()
	}
}

func (s *Server) Stop() {
	if s.stopped.Swap(true) {
		return
	}
	s.state.Set(connection.StateStopping)
	s.cancel()
	if err := s.listener.Close(); err != nil {
		logging.Debugf("listener close: %v", err)
	}
}

func (s *Server) cleanup() {
	s.state.Set(connection.StateStopping)
	logging.Logf("removing iptables rules and routes...")
	if s.netSetup != nil {
		if err := s.netSetup.CleanupErr(); err != nil {
			logging.Errorf("network cleanup incomplete: %v", err)
		}
	}
	if err := s.tun.Close(); err != nil {
		logging.Debugf("TUN close: %v", err)
	}
	s.state.Set(connection.StateDisconnected)
	logging.Logf("shutdown complete")
}

func listenPort(addr string) int {
	_, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		logging.Warnf("listenPort: could not parse %q: %v — defaulting to 8765", addr, err)
		return 8765
	}
	var port int
	if _, err := fmt.Sscanf(portStr, "%d", &port); err != nil || port <= 0 || port > 65535 {
		logging.Warnf("listenPort: invalid port %q — defaulting to 8765", portStr)
		return 8765
	}
	return port
}
