package relay

type Config struct {
	ListenAddr string
	TunName string
	TunAddr string
	TunPrefix int
	AndroidIP string
	OutIface string
	MTU int
	
	Verbose bool
}