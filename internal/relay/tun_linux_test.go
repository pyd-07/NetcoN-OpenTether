//go:build linux

package relay

import (
	"errors"
	"io"
	"testing"
)

func TestTunWriteShortWrite(t *testing.T) {
	tun := &TunDevice{
		fd: -1,
		writeFn: func(_ int, p []byte) (int, error) {
			return len(p) - 1, nil
		},
	}

	n, err := tun.Write([]byte("hello"))
	if !errors.Is(err, io.ErrShortWrite) {
		t.Fatalf("Write error = %v, want io.ErrShortWrite", err)
	}
	if n != 4 {
		t.Fatalf("Write count = %d, want 4", n)
	}
}

func TestTunWriteFullWrite(t *testing.T) {
	tun := &TunDevice{
		fd: -1,
		writeFn: func(_ int, p []byte) (int, error) {
			return len(p), nil
		},
	}

	n, err := tun.Write([]byte("hello"))
	if err != nil {
		t.Fatalf("Write: %v", err)
	}
	if n != 5 {
		t.Fatalf("Write count = %d, want 5", n)
	}
}

func TestTunIFFlagsAreExclusive(t *testing.T) {
	flags := tunIFFlags()
	if flags&iffTUN == 0 || flags&iffNOPI == 0 {
		t.Fatalf("flags = %#x, want TUN and NOPI set", flags)
	}
	if flags&iffTUNEXCL == 0 {
		t.Fatalf("flags = %#x, want IFF_TUN_EXCL set", flags)
	}
}
