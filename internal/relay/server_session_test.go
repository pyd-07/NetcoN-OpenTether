//go:build linux

package relay

import (
	"context"
	"net"
	"testing"
	"time"

	"github.com/pyd-07/opentether/internal/connection"
	"github.com/pyd-07/opentether/internal/protocol"
)

// A reconnect must not queue behind a stale or half-open session. The relay
// keeps one live session: the new connection replaces the old one.
func TestServerPreemptsPreviousSession(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}

	rec := newTunWriteRecorder(4)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	srv := &Server{
		cfg:      testValidConfig(),
		tun:      rec.device(),
		listener: ln,
		ctx:      ctx,
		cancel:   cancel,
		state:    connection.NewConnectionStateTracker(),
	}

	serveDone := make(chan error, 1)
	go func() { serveDone <- srv.serve() }()

	first, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial first: %v", err)
	}
	defer first.Close()

	waitForCondition(t, "first session connected", func() bool {
		state, _ := srv.state.Snapshot()
		return state == connection.StateConnected
	})

	second, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial second: %v", err)
	}
	defer second.Close()

	// A PING may arrive before tunToAndroid installs the frame writer, in
	// which case the reply is intentionally dropped. Retry until the new
	// session is fully serving.
	deadline := time.Now().Add(5 * time.Second)
	var pong protocol.Frame
	for {
		if _, err := second.Write(protocol.BuildFrame(protocol.Frame{MsgType: protocol.MsgPing})); err != nil {
			t.Fatalf("write PING: %v", err)
		}
		if err := second.SetReadDeadline(time.Now().Add(200 * time.Millisecond)); err != nil {
			t.Fatalf("set read deadline: %v", err)
		}
		pong, err = protocol.ReadFrame(second)
		if err == nil && pong.MsgType == protocol.MsgPong {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("new session never answered PING: frame=%v err=%v", pong, err)
		}
		time.Sleep(50 * time.Millisecond)
	}

	// The first, idle session must have been closed by preemption.
	if err := first.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("set first deadline: %v", err)
	}
	if _, err := first.Read(make([]byte, 1)); err == nil {
		t.Fatal("previous session remained open after a new connection arrived")
	}

	cancel()
	if err := ln.Close(); err != nil {
		t.Fatalf("close listener: %v", err)
	}
	select {
	case err := <-serveDone:
		if err != nil {
			t.Fatalf("serve returned error: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("serve did not stop after cancellation")
	}
}
