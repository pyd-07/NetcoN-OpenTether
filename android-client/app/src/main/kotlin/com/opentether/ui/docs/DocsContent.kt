package com.opentether.ui.docs

sealed interface DocsBlock {
    data class Heading(val text: String) : DocsBlock
    data class Paragraph(val text: String) : DocsBlock
    data class Bullets(val items: List<String>) : DocsBlock
    data class Code(val text: String) : DocsBlock
    data class Note(val text: String) : DocsBlock
    data class Link(val label: String, val url: String) : DocsBlock
}

data class DocsTopic(
    val id: String,
    val title: String,
    val summary: String,
    val blocks: List<DocsBlock>,
)

/**
 * Concise in-app documentation. The canonical setup and validation runbook
 * remains in README.md and docs/adb-integration.md; this model mirrors the
 * user-relevant parts while keeping the app free of a Markdown dependency.
 */
object DocsContent {
    const val REPOSITORY_URL = "https://github.com/pyd-07/NetcoN-OpenTether"
    const val ADB_GUIDE_URL = "$REPOSITORY_URL/blob/main/docs/adb-integration.md"

    val topics: List<DocsTopic> = listOf(
        DocsTopic(
            id = "getting-started",
            title = "Getting started",
            summary = "What you need, and how the first connection works.",
            blocks = listOf(
                DocsBlock.Paragraph(
                    "OpenTether sends this phone's internet traffic through a computer over USB. " +
                        "The phone runs the app; the computer runs the OpenTether relay.",
                ),
                DocsBlock.Heading("Before you start"),
                DocsBlock.Bullets(
                    listOf(
                        "An Android 8.0+ phone and a USB data cable.",
                        "A Linux computer with an internet connection.",
                        "The OpenTether relay running on that computer.",
                        "USB debugging enabled for the recommended ADB transport.",
                    ),
                ),
                DocsBlock.Heading("First connection"),
                DocsBlock.Bullets(
                    listOf(
                        "Start the relay on the computer and wait for it to report that it is ready.",
                        "Connect the phone by USB and enable USB debugging if you are using ADB.",
                        "Open OpenTether and tap Connect.",
                        "Allow Android's VPN permission prompt.",
                    ),
                ),
                DocsBlock.Paragraph(
                    "Android asks for VPN permission because OpenTether uses the system VpnService " +
                        "to route packets. The prompt is normal and cannot be bypassed by the app.",
                ),
                DocsBlock.Code("adb devices\nadb reverse --list"),
                DocsBlock.Note(
                    "ADB is the recommended transport. USB Accessory (AOA) is experimental and " +
                        "should be used only when you are testing that path.",
                ),
            ),
        ),
        DocsTopic(
            id = "how-it-works",
            title = "How it works",
            summary = "VpnService, USB transport, relay, and packet flow.",
            blocks = listOf(
                DocsBlock.Paragraph(
                    "OpenTether creates a local VPN interface on the phone, captures IP packets, " +
                        "and carries them over the USB transport to the relay. The relay writes the " +
                        "packets into its TUN interface and uses the host's NAT configuration to reach " +
                        "the internet. Replies follow the same path back.",
                ),
                DocsBlock.Bullets(
                    listOf(
                        "ADB transport: the app connects to 127.0.0.1:8765 through an adb reverse mapping.",
                        "AOA transport: the app uses an Android Open Accessory link instead of adb reverse.",
                        "The relay owns the host-side TUN/NAT setup and forwards packet data.",
                        "OpenTether frames are carried as OTP data, connect, close, error, and ping messages.",
                    ),
                ),
                DocsBlock.Note(
                    "There is no third-party VPN server or cloud relay in this design. The computer " +
                        "you connect to is the exit point for the traffic.",
                ),
            ),
        ),
        DocsTopic(
            id = "lifecycle",
            title = "Connection lifecycle",
            summary = "What each state means and what happens when things stop.",
            blocks = listOf(
                DocsBlock.Bullets(
                    listOf(
                        "Not connected: no VPN session is active.",
                        "Waiting for permission: Android's VPN consent dialog is open.",
                        "Connecting: the VPN interface is being prepared and the transport is being opened.",
                        "Waiting for your computer: the app needs the relay, USB cable, or accessory prompt.",
                        "Connected: the tunnel is established and packets are being forwarded.",
                        "Disconnecting: the service is closing the tunnel and releasing resources.",
                    ),
                ),
                DocsBlock.Paragraph(
                    "Canceling while the app is starting closes the pending session. Disconnecting " +
                        "from Connected stops the foreground service and releases the VPN interface.",
                ),
                DocsBlock.Paragraph(
                    "If the transport fails, the runtime may report an error while the service is " +
                        "still running. In that state the primary action is Disconnect; use Connection " +
                        "details to inspect the failure before retrying.",
                ),
                DocsBlock.Note(
                    "Rotating the screen or recreating the activity does not intentionally restart the " +
                        "VPN. The service and runtime holder own the session state.",
                ),
            ),
        ),
        DocsTopic(
            id = "troubleshooting",
            title = "Troubleshooting",
            summary = "Common problems, checks, and a safe way to retry.",
            blocks = listOf(
                DocsBlock.Heading("The app is waiting for my computer"),
                DocsBlock.Bullets(
                    listOf(
                        "Confirm the relay is running and has finished its startup checks.",
                        "Check the USB cable and that the phone is still connected.",
                        "For ADB, confirm USB debugging is enabled and the device is authorized.",
                        "Check the reverse mapping, or recreate it with the command below.",
                    ),
                ),
                DocsBlock.Code("adb devices\nadb reverse --list\nadb reverse tcp:8765 tcp:8765"),
                DocsBlock.Heading("VPN permission was denied"),
                DocsBlock.Paragraph(
                    "Tap Try again in the app and allow Android's VPN prompt. If the prompt does not " +
                        "appear, close and reopen the app, then try again.",
                ),
                DocsBlock.Heading("The relay reported a problem"),
                DocsBlock.Paragraph(
                    "Open Connection details, copy the sanitized summary if needed, and check the " +
                        "relay output on the computer. Disconnect, restart the relay, and reconnect.",
                ),
                DocsBlock.Heading("Connected but there is no internet"),
                DocsBlock.Bullets(
                    listOf(
                        "A connected VPN session does not by itself prove that the host can forward traffic.",
                        "Test IPv4 directly before testing DNS names.",
                        "If only names fail, check the DNS server in Connection settings and on the host.",
                        "IPv6 exposure depends on the host configuration and is not covered by the default checks.",
                    ),
                ),
                DocsBlock.Code("adb shell ping -c 3 1.1.1.1\nadb shell ping -c 3 google.com"),
                DocsBlock.Heading("USB Accessory mode"),
                DocsBlock.Bullets(
                    listOf(
                        "AOA is experimental. Prefer ADB unless you are specifically testing it.",
                        "Reconnect the cable and accept the USB accessory prompt when Android shows it.",
                        "Confirm the relay was started in the mode that supports the accessory link.",
                    ),
                ),
                DocsBlock.Note(
                    "Battery optimization can interrupt long-lived background sessions. Use the device " +
                        "diagnostics on this screen to check the current battery-optimization state.",
                ),
            ),
        ),
        DocsTopic(
            id = "technical-reference",
            title = "Technical reference",
            summary = "Ports, addresses, transports, and current limits.",
            blocks = listOf(
                DocsBlock.Bullets(
                    listOf(
                        "Minimum Android version: 8.0 (API 26).",
                        "ADB relay endpoint: 127.0.0.1:8765 through adb reverse.",
                        "Phone VPN address: 10.0.0.1/24.",
                        "Relay-side VPN address: 10.0.0.2.",
                        "Default MTU: 1400.",
                        "Default DNS server: 8.8.8.8; it can be overridden in Connection settings.",
                        "AOA uses the USB accessory filter declared by the app and is experimental.",
                    ),
                ),
                DocsBlock.Paragraph(
                    "The relay must run with the privileges required to create and configure its TUN " +
                        "interface and NAT rules. The Android app does not configure host networking.",
                ),
                DocsBlock.Heading("Current limitations"),
                DocsBlock.Bullets(
                    listOf(
                        "AOA is experimental and less validated than ADB.",
                        "Deliberate DNS, HTTPS, large-transfer, IPv6, and reconnect scenarios remain outstanding validation work.",
                        "The app supports the transports implemented in this repository; there are no additional platform guarantees.",
                        "The relay and Android app must be compatible versions.",
                    ),
                ),
            ),
        ),
        DocsTopic(
            id = "privacy-security",
            title = "Privacy and security",
            summary = "Where traffic goes and which component you must trust.",
            blocks = listOf(
                DocsBlock.Bullets(
                    listOf(
                        "The app routes IP packets from its VpnService to the relay on your computer.",
                        "The relay is the exit node: its host network, DNS, and NAT configuration affect your traffic.",
                        "No account, cloud VPN service, or remote relay is used by the current implementation.",
                        "Connection diagnostics are assembled on-device and leave the app only if you copy them.",
                    ),
                ),
                DocsBlock.Heading("Trust boundary"),
                DocsBlock.Bullets(
                    listOf(
                        "Treat the connected computer as trusted infrastructure.",
                        "The ADB application layer is not authenticated. A local process that can reach the relay's loopback listener can inject OTP frames.",
                        "Use a cable and computer you control; avoid untrusted USB charging or debugging stations.",
                        "The system VPN consent prompt is the Android-level authorization boundary.",
                    ),
                ),
                DocsBlock.Note(
                    "OpenTether does not provide anonymity. It moves the traffic exit point to the " +
                        "computer's network.",
                ),
            ),
        ),
        DocsTopic(
            id = "developer-info",
            title = "Developer information",
            summary = "Architecture, build commands, and repository documentation.",
            blocks = listOf(
                DocsBlock.Bullets(
                    listOf(
                        "Android app: Kotlin, Jetpack Compose, VpnService, ADB/AOA tunnel clients.",
                        "Relay: Go service that owns the host tunnel, NAT, and relay protocol.",
                        "The app and relay communicate with the OTP frame format defined in the repository.",
                    ),
                ),
                DocsBlock.Heading("Build and test"),
                DocsBlock.Code(
                    "cd android-client\n" +
                        "./gradlew testDebugUnitTest --no-daemon\n" +
                        "./gradlew assembleDebug --no-daemon",
                ),
                DocsBlock.Code(
                    "go test -race -count=1 -timeout=120s ./...\n" +
                        "go vet ./...\n" +
                        "make test-integration",
                ),
                DocsBlock.Paragraph(
                    "The canonical ADB setup and validation runbook is docs/adb-integration.md. " +
                        "Keep this screen and the repository documentation in sync when behavior changes.",
                ),
                DocsBlock.Link("Open the repository", REPOSITORY_URL),
                DocsBlock.Link("Open the ADB integration guide", ADB_GUIDE_URL),
            ),
        ),
    )
}
