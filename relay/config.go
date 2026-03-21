package relay

// Config holds all relay configuration. Zero value is not valid;
// use the defaults in main.go.
type Config struct {
	ListenAddr string
	TunName    string
	TunAddr    string
	TunPrefix  int
	AndroidIP  string
	OutIface   string
	MTU        int

	// DisableAdbWatch disables automatic `adb reverse` setup.
	// Set to true if you prefer to run `adb reverse` manually or
	// if adb is not installed on the PC.
	DisableAdbWatch bool

	// AOAMode switches the session write path from FlushWriter (TCP batching)
	// to DirectWriter (one USB transfer per OTP frame).
	//
	// Set automatically by RunAOA; do NOT set this manually for TCP mode.
	//
	// Background: USB bulk OUT transfers are discrete, not a byte stream.
	// Android's /dev/usb_accessory driver returns one USB transfer per read()
	// syscall. Batching multiple OTP frames into one epOut.Write call causes
	// framing corruption on Android because DataInputStream.readFully may not
	// span USB transfer boundaries on all devices.
	AOAMode bool

	Verbose bool
}
