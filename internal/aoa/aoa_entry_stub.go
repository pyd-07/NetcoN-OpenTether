//go:build !(linux && aoa)

package aoa

import (
	"fmt"

	"github.com/pyd-07/opentether/internal/config"
)

// RunAOA is a stub for non-AOA builds.
// Rebuild with -tags aoa on Linux to get the real implementation.
func RunAOA(_ config.Config, _ <-chan struct{}) error {
	return fmt.Errorf(
		"AOA support was not compiled in.\n" +
			"Rebuild with:  go build -tags aoa -o relay-bin ./cmd/opentether\n" +
			"Requires:      sudo apt install libusb-1.0-0-dev",
	)
}
