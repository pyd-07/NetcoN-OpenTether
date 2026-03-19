module github.com/pyd-07/opentether

go 1.25.0

require (
	// AOA USB transport (build with -tags aoa)
	// Requires: apt install libusb-1.0-0-dev
	github.com/google/gousb v1.1.3
	golang.org/x/sys v0.42.0
)
