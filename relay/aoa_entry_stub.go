//go:build !(linux && aoa)

package relay

import "fmt"

// RunAOA is a stub for non-AOA builds.
// Rebuild with -tags aoa on Linux to get the real implementation.
func RunAOA(_ Config, _ <-chan struct{}) error {
	return fmt.Errorf(
		"AOA support was not compiled in.\n" +
			"Rebuild with:  go build -tags aoa ./...\n" +
			"Requires:      sudo apt install libusb-1.0-0-dev",
	)
}
