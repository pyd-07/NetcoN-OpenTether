package relay

import (
	"encoding/xml"
	"os"
	"testing"
)

type accessoryFilterXML struct {
	Accessory struct {
		Manufacturer string `xml:"manufacturer,attr"`
		Model        string `xml:"model,attr"`
		Version      string `xml:"version,attr"`
	} `xml:"usb-accessory"`
}

// Android matches USB accessory filters by exact string equality. This test
// reads the real table that negotiateAccessory iterates over, so it protects
// the values actually sent on the wire, not just a standalone constant.
func TestAOAStringTableMatchesAccessoryFilter(t *testing.T) {
	const filterPath = "../android-client/app/src/main/res/xml/usb_accessory_filter.xml"

	data, err := os.ReadFile(filterPath)
	if err != nil {
		t.Fatalf("read %s: %v", filterPath, err)
	}

	var filter accessoryFilterXML
	if err := xml.Unmarshal(data, &filter); err != nil {
		t.Fatalf("parse %s: %v", filterPath, err)
	}
	if filter.Accessory.Version == "" {
		t.Fatalf("no accessory version found in %s", filterPath)
	}

	checks := []struct {
		field string
		got   string
		want  string
	}{
		{"manufacturer", aoaStrings[aoaIdxManufacturer], filter.Accessory.Manufacturer},
		{"model", aoaStrings[aoaIdxModel], filter.Accessory.Model},
		{"version", aoaStrings[aoaIdxVersion], filter.Accessory.Version},
	}
	for _, check := range checks {
		if check.got != check.want {
			t.Errorf("AOA %s mismatch: relay sends %q, Android filter requires %q",
				check.field, check.got, check.want)
		}
	}
}

// The accessory protocol assigns fixed indices to each string. If the table is
// reordered, Android receives the wrong value even if the individual strings
// are still present.
func TestAOAStringTableOrdering(t *testing.T) {
	if len(aoaStrings) != aoaStringCount {
		t.Fatalf("aoaStrings length = %d, want %d", len(aoaStrings), aoaStringCount)
	}

	indices := []int{
		aoaIdxManufacturer,
		aoaIdxModel,
		aoaIdxDescription,
		aoaIdxVersion,
		aoaIdxURI,
		aoaIdxSerial,
	}
	for want, got := range indices {
		if got != want {
			t.Fatalf("AOA string index %d = %d, want %d", want, got, want)
		}
	}

	if aoaStrings[aoaIdxVersion] != aoaAccessoryVersion {
		t.Fatalf("version entry = %q, want constant %q",
			aoaStrings[aoaIdxVersion], aoaAccessoryVersion)
	}
}
