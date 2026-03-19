package relay

type Config struct {
	ListenAddr string
	TunName    string
	TunAddr    string
	TunPrefix  int
	AndroidIP  string
	OutIface   string
	MTU        int

	// DisableAdbWatch disables the automatic `adb reverse` setup.
	// Set to true if you prefer to run `adb reverse` manually or if
	// adb is not installed on the PC.
	DisableAdbWatch bool

	Verbose bool
}