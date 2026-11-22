# Bugsink 134 connection regressions

Run `python3 app/src/test/host-bugsink/run.py` with a JDK on PATH, after Gradle has cached
this project's Kotlin compiler dependencies.

This executes the complete production SocketProjectionConnection on real JVM loopback sockets:
20 blocked-reader/local-close races, disconnected state and genuine peer reset reporting.
The NativeAA credential wait and exception handlers are extracted from production, with
an injected clock and readback callbacks. Cases cover late valid BSSID, permanently masked
BSSID at the existing 60-second deadline, stopped/terminal sessions, ready credentials,
cancellation propagation and real handshake errors. The actual WPP state machine and
credential validation policy are compiled. No raw events, vehicle, Bluetooth or WiFi APIs
are required. These checks do not establish physical head-unit compatibility.

The transport runs the bundled Android Auto revision 6 WebAssembly through the production
PolicyProgram interpreter. The body-read regression compiles the production single-message
reader, header decoder, recovery policy and extracted poll callback. It injects timeout after
0, 300 and 4565 bytes of a 4566-byte body, plus EOF and success controls. Failure must remain
visible, reach quit(clean=false), and queue no subsequent read or decrypt/delivery. Android,
TLS and downstream handler objects are doubles; this does not diagnose a physical radio fault.
