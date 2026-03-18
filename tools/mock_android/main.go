package main
// mock_android simulates what the Android VPN client would do:
// 1. Connect to relay TCP port
// 2. Send a raw DNS query (UDP to 8.8.8.8:53) wrapped in an OTP DATA frame
// 3. Print the OTP frame received back

import (
	"encoding/binary"
	"log"
	"net"
	"time"
)

const (
	frameHeaderSize = 12
	msgData         uint8 = 0x01
)

func main() {
	conn, err := net.Dial("tcp", "127.0.0.1:8765")
	if err != nil {
		log.Fatalf("connect: %v", err)
	}
	defer conn.Close()
	log.Println("connected to relay")

	// Build a raw IPv4 UDP DNS query packet for "example.com" → 8.8.8.8:53
	pkt := buildDNSPacket()
	log.Printf("sending %d-byte DNS query packet", len(pkt))

	// Wrap in OTP frame
	if err := writeFrame(conn, 1001, msgData, pkt); err != nil {
		log.Fatalf("send frame: %v", err)
	}

	// Wait for response
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	hdr := make([]byte, frameHeaderSize)
	if _, err := readFull(conn, hdr); err != nil {
		log.Fatalf("read response header: %v", err)
	}
	payLen := binary.BigEndian.Uint32(hdr[4:8])
	msgType := hdr[8]
	payload := make([]byte, payLen)
	if payLen > 0 {
		if _, err := readFull(conn, payload); err != nil {
			log.Fatalf("read response payload: %v", err)
		}
	}
	log.Printf("got frame: type=0x%02x payload=%d bytes", msgType, len(payload))
	if msgType == msgData && len(payload) > 0 {
		log.Printf("first 40 bytes of IP response: %x", payload[:min(40, len(payload))])
		log.Println("SUCCESS — relay is forwarding packets")
	}
}

func writeFrame(conn net.Conn, connID uint32, msgType uint8, payload []byte) error {
	hdr := make([]byte, frameHeaderSize)
	binary.BigEndian.PutUint32(hdr[0:4], connID)
	binary.BigEndian.PutUint32(hdr[4:8], uint32(len(payload)))
	hdr[8] = msgType
	if _, err := conn.Write(hdr); err != nil {
		return err
	}
	_, err := conn.Write(payload)
	return err
}

func readFull(conn net.Conn, buf []byte) (int, error) {
	total := 0
	for total < len(buf) {
		n, err := conn.Read(buf[total:])
		total += n
		if err != nil {
			return total, err
		}
	}
	return total, nil
}

// buildDNSPacket constructs a raw IPv4/UDP packet containing a DNS query
// for "example.com" sent to 8.8.8.8:53, with src IP 10.0.0.1.
func buildDNSPacket() []byte {
	// DNS query payload for "example.com" type A
	dnsQuery := []byte{
		0xAB, 0xCD, // transaction ID
		0x01, 0x00, // flags: standard query, recursion desired
		0x00, 0x01, // QDCOUNT: 1 question
		0x00, 0x00, // ANCOUNT: 0
		0x00, 0x00, // NSCOUNT: 0
		0x00, 0x00, // ARCOUNT: 0
		// QNAME: example.com
		0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
		0x03, 'c', 'o', 'm', 0x00,
		0x00, 0x01, // QTYPE: A
		0x00, 0x01, // QCLASS: IN
	}

	srcIP := [4]byte{10, 0, 0, 1}   // Android VPN IP
	dstIP := [4]byte{8, 8, 8, 8}    // Google DNS
	srcPort := uint16(54321)
	dstPort := uint16(53)

	// UDP header
	udpLen := uint16(8 + len(dnsQuery))
	udp := make([]byte, udpLen)
	binary.BigEndian.PutUint16(udp[0:2], srcPort)
	binary.BigEndian.PutUint16(udp[2:4], dstPort)
	binary.BigEndian.PutUint16(udp[4:6], udpLen)
	binary.BigEndian.PutUint16(udp[6:8], 0) // checksum (optional for IPv4 UDP)
	copy(udp[8:], dnsQuery)

	// IPv4 header (20 bytes, no options)
	totalLen := uint16(20 + len(udp))
	ip := make([]byte, 20)
	ip[0] = 0x45             // version=4, IHL=5
	ip[1] = 0                // DSCP/ECN
	binary.BigEndian.PutUint16(ip[2:4], totalLen)
	binary.BigEndian.PutUint16(ip[4:6], 0xABCD) // ID
	ip[6] = 0x40             // flags: don't fragment
	ip[8] = 64               // TTL
	ip[9] = 17               // protocol: UDP
	copy(ip[12:16], srcIP[:])
	copy(ip[16:20], dstIP[:])
	binary.BigEndian.PutUint16(ip[10:12], ipChecksum(ip))

	return append(ip, udp...)
}

func ipChecksum(hdr []byte) uint16 {
	var sum uint32
	for i := 0; i < len(hdr); i += 2 {
		sum += uint32(hdr[i])<<8 | uint32(hdr[i+1])
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}