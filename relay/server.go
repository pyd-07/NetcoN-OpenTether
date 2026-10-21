package relay

import (
	"context"
	"fmt"
	"net"
	"sync"
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

	// adbWG tracks the ADB watcher so shutdown can wait for its reverse-mapping
	// cleanup before the process exits.
	adbWG sync.WaitGroup
}

func NewServer(cfg Config) (*Server, error) {
	if cfg.AOAMode {
		return nil, fmt.Errorf("invalid config: NewServer is TCP-only; use RunAOA for AOA mode")
	}

	setVerbose(cfg.Verbose)
	if err := cfg.Validate(); err != nil {
		return nil, fmt.Errorf("invalid config: %w", err)
	}

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
	// Wait for the watcher (and any in-flight reverse setup) before network
	// cleanup. Defers run LIFO, so this executes before cleanup.
	defer s.adbWG.Wait()

	if !s.cfg.DisableAdbWatch {
		port := listenPort(s.cfg.ListenAddr)
		watcher := NewAdbWatcher(port, s.cfg.AdbBin, s.cfg.AdbPort)
		s.adbWG.Add(1)
		go func() {
			defer s.adbWG.Done()
			watcher.Watch(s.ctx)
		}()
		logf("ADB watcher started — automatic device detection and reconnect enabled")
	}

	s.state.Set(StateDisconnected)
	logf("ready — waiting for Android on %s", s.cfg.ListenAddr)
	return s.serve()
}

// serve accepts Android connections and owns at most one live session. A new
// connection replaces an existing one: the Android client opens a new TCP
// connection only after its previous transport failed, so a half-open or
// wedged session must not block reconnection forever.
func (s *Server) serve() error {
	var active *session
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
			errorf("accept: %v", err)
			continue
		}

		if tc, ok := conn.(*net.TCPConn); ok {
			if err := tc.SetKeepAlive(true); err != nil {
				debugf("TCP keepalive setup for %s failed: %v", conn.RemoteAddr(), err)
			}
		}

		if active != nil {
			warnf("new Android connection from %s — replacing previous session from %s",
				conn.RemoteAddr(), active.conn.RemoteAddr())
			active.conn.Close()
			<-activeDone
			active = nil
			activeDone = nil
		}

		s.state.Set(StateConnected)
		logf("Android connected from %s — session starting", conn.RemoteAddr())

		sess := newSession(conn, s.tun, s.cfg)
		done := make(chan struct{})
		active = sess
		activeDone = done
		go func() {
			defer close(done)
			sess.run(s.ctx)
			s.state.Set(StateDisconnected)
			logf("Android session ended — waiting for reconnect")
		}()
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
	if s.netSetup != nil {
		if err := s.netSetup.CleanupErr(); err != nil {
			errorf("network cleanup incomplete: %v", err)
		}
	}
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
