package relay

// aoaAccessoryVersion is the version string sent to Android during AOA
// negotiation. Android matches accessory filters by exact string equality, so
// this must stay identical to the version attribute in
// android-client/app/src/main/res/xml/usb_accessory_filter.xml.
//
// Compatibility: APKs built before the filter was changed to "1" used "1.0".
// A single relay version string cannot match both filters, so those older
// APKs must be rebuilt. This is a known compatibility break, not a wire-format
// change.
const aoaAccessoryVersion = "1"
