package logging

import "log"

var globalVerbose bool

// SetVerbose enables or disables per-packet debug logging.
func SetVerbose(v bool) {
	globalVerbose = v
}

// Logf logs a normal operational message (always shown).
func Logf(format string, args ...any) {
	log.Printf("[relay] "+format, args...)
}

// Debugf logs a per-packet or high-frequency message (only shown with -v).
func Debugf(format string, args ...any) {
	if globalVerbose {
		log.Printf("[debug] "+format, args...)
	}
}

// Warnf logs a recoverable anomaly (always shown).
func Warnf(format string, args ...any) {
	log.Printf("[warn]  "+format, args...)
}

// Errorf logs a non-fatal error (always shown).
func Errorf(format string, args ...any) {
	log.Printf("[error] "+format, args...)
}
