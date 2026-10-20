package relay

// AOA accessory descriptor string indices. The Android Open Accessory protocol
// defines these positions; Android matches the USB accessory filter against
// the values at these indices. Keep this table in sync with
// android-client/app/src/main/res/xml/usb_accessory_filter.xml.
const (
	aoaIdxManufacturer = 0
	aoaIdxModel        = 1
	aoaIdxDescription  = 2
	aoaIdxVersion      = 3
	aoaIdxURI          = 4
	aoaIdxSerial       = 5

	aoaStringCount = 6
)

// aoaStrings is the actual table sent by negotiateAccessory. It lives outside
// aoa_linux.go so ordinary (non-AOA-tagged) unit tests can verify the wire
// contract against the Android accessory filter.
var aoaStrings = [aoaStringCount]string{
	"OpenTether",
	"OpenTether Relay",
	"Reverse USB tethering — no root required",
	aoaAccessoryVersion,
	"https://github.com/pyd-07/NetcoN-OpenTether",
	"opentether-relay-01",
}
