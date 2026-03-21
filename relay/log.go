package relay

import "log"

var globalVerbose bool

func setVerbose(v bool) {
	globalVerbose = v
}

// logf logs a normal operational message (always shown).
func logf(format string, args ...any) {
	log.Printf("[relay] "+format, args...)
}

// debugf logs a per-packet or high-frequency message (only shown with -v).
func debugf(format string, args ...any) {
	if globalVerbose {
		log.Printf("[debug] "+format, args...)
	}
}

// warnf logs a recoverable anomaly (always shown).
func warnf(format string, args ...any) {
	log.Printf("[warn]  "+format, args...)
}

// errorf logs a non-fatal error (always shown).
func errorf(format string, args ...any) {
	log.Printf("[error] "+format, args...)
}
