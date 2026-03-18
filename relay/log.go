package relay

import "log"

var globalVerbose bool

func setVerbose(v bool)  {
	globalVerbose = v
}

func logf(format string, args ...any)  {
	log.Printf("[relay] "+format, args...)
}

func debugf(format string, args ...any)  {
	if globalVerbose {
		log.Printf("[debug] "+format, args...)
	}
}

func errorf(format string, args ...any)  {
	log.Printf("[error] "+format, args...)
}