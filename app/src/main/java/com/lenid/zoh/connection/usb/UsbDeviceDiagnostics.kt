package com.lenid.zoh.connection.usb

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.lenid.zoh.utils.AppLog
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dumps the raw USB bus alongside our own verdict on each device. Every enumeration site filters
 * with [UsbDeviceCompat.isAndroidDevice], so a user reporting "no USB device found" could mean
 * nothing was on the bus or that we rejected what was there, and the logs could not tell them
 * apart. An empty result is the case worth reporting, so it prints at INFO; otherwise Verbose.
 */
object UsbDeviceDiagnostics {

    /** Last dump per caller, so a repeated scan of an unchanged bus does not repeat itself. */
    private val lastSignatures = ConcurrentHashMap<String, String>()

    /** (scanned at, the whole bus) for every scan, trimmed to the hint window. */
    private val seenScans = Collections.synchronizedList(mutableListOf<Pair<Long, Set<String>>>())

    /** The host-support verdict is a fact about the unit, so it is worth saying exactly once. */
    private val hostSupportReported = AtomicBoolean(false)

    /** Whether the framework will enumerate anything at all on this unit's USB bus. */
    fun hasUsbHostFeature(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    fun logDeviceList(context: Context, usbManager: UsbManager, caller: String) {
        val devices = try {
            usbManager.deviceList.values.toList()
        } catch (e: Throwable) {
            // Some head unit ROMs throw out of getDeviceList() rather than returning empty.
            AppLog.e("UsbDiagnostics: $caller could not read the USB device list: ${e.message}")
            return
        }

        val accepted = devices.count { UsbDeviceCompat.isConnectable(context, it) }
        val reportAtInfo = accepted == 0
        val lines = devices.map { "UsbDiagnostics:   ${describe(context, it, usbManager)}" }
        val (recentIdentities, compositionChanges) = recordScan(devices)

        val hint = UsbBusHintPolicy.hint(
            devices.size, accepted, compositionChanges, hasUsbHostFeature(context)
        )

        // Above the dedup, because this verdict never changes: an unchanged empty bus returns early
        // below, so a reporter who only ever scans an empty bus would otherwise never see it.
        if (hint == UsbBusHintPolicy.Hint.NO_HOST_SUPPORT && hostSupportReported.compareAndSet(false, true)) {
            AppLog.i(
                "UsbDiagnostics: this head unit does not declare USB host support " +
                    "(android.hardware.usb.host), so Android enumerates nothing on the bus whatever " +
                    "is plugged in and wired Android Auto cannot work here. Storage sticks and vendor " +
                    "mirroring apps are unaffected: they do not go through the Android USB API."
            )
        }

        // The service scan runs on every attach, detach and permission result, so repeating an
        // unchanged bus would bury the change that matters in a log the user has to read. Keyed by
        // caller as well, so pressing the USB button always says what it saw.
        val signature = "$accepted/${devices.size}|" + lines.joinToString("|")
        if (lastSignatures.put(caller, signature) == signature) return

        val header = "UsbDiagnostics: $caller sees ${devices.size} USB device(s), " +
            "$accepted usable for Android Auto"
        if (reportAtInfo) AppLog.i(header) else AppLog.v(header)

        for (line in lines) {
            if (reportAtInfo) AppLog.i(line) else AppLog.v(line)
        }

        // The common causes, in the order they are worth checking, so the log answers the question
        // without a round trip to the reporter.
        when (hint) {
            // Already reported above, and only once per process.
            UsbBusHintPolicy.Hint.NO_HOST_SUPPORT -> Unit
            UsbBusHintPolicy.Hint.EMPTY_BUS -> AppLog.i(
                "UsbDiagnostics: nothing is on the bus. Either the port carries no data, the unit " +
                    "is not in USB host mode, or a wireless adapter is waiting for its phone " +
                    "before it presents itself."
            )
            UsbBusHintPolicy.Hint.CYCLING_ADAPTER -> AppLog.i(
                "UsbDiagnostics: the bus changed $compositionChanges times in the last minute " +
                    "(${recentIdentities.joinToString(", ")}) and none of what came and went " +
                    "offers Android Auto. A wireless adapter that keeps re-enumerating has not " +
                    "found its phone: pair the adapter with the phone, not with this head unit."
            )
            UsbBusHintPolicy.Hint.NONE_USABLE -> AppLog.i(
                "UsbDiagnostics: everything on the bus was refused, for the reason on each line " +
                    "above. Nothing here is offering Android Auto over USB."
            )
            null -> Unit
        }
    }

    /**
     * Adds this scan's whole bus to the window, and returns what is still inside it: the distinct
     * identities to name, and how many times the composition moved.
     *
     * The composition is recorded as one entry rather than one per device, because two devices that
     * merely sit there side by side are not a device that keeps changing its identity.
     */
    private fun recordScan(devices: List<UsbDevice>): Pair<List<String>, Int> {
        val now = System.currentTimeMillis()
        synchronized(seenScans) {
            seenScans.add(now to devices.map { vidPid(it) }.toSet())
            seenScans.removeAll { now - it.first >= UsbBusHintPolicy.IDENTITY_WINDOW_MS }
            val scans = seenScans.toList()
            val identities = scans.flatMap { scan -> scan.second.map { scan.first to it } }
            return UsbBusHintPolicy.identitiesInWindow(identities, now) to
                UsbBusHintPolicy.compositionChanges(scans, now)
        }
    }

    private fun vidPid(device: UsbDevice): String =
        String.format(Locale.US, "%04X:%04X", device.vendorId, device.productId)

    /** One line per device: identity, our verdict, and every interface descriptor behind it. */
    fun describe(context: Context, device: UsbDevice, usbManager: UsbManager): String {
        val sb = StringBuilder()
        sb.append(UsbDeviceCompat.getUniqueName(device))
        sb.append(" [")
        sb.append(String.format(Locale.US, "class %02X/%02X/%02X",
            device.deviceClass, device.deviceSubclass, device.deviceProtocol))
        sb.append(if (usbManager.hasPermission(device)) ", permission" else ", no permission")
        sb.append("] ")
        sb.append(UsbDeviceCompat.connectableReason(context, device))

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            sb.append(String.format(Locale.US, " | if%d %02X/%02X/%02X",
                i, iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol))
            sb.append(" ").append(endpointSummary(iface))
        }
        return sb.toString()
    }

    /**
     * Endpoint shape matters because an accessory interface needs a bulk IN and a bulk OUT. Counts
     * rather than flags: a phone in file transfer and a vendor ethernet controller both come out as
     * "bulkIn+bulkOut", and the second bulk OUT is the only thing in the shape that differs.
     */
    private fun endpointSummary(iface: UsbInterface): String {
        var bulkIn = 0
        var bulkOut = 0
        var others = 0
        for (j in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(j)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn++ else bulkOut++
            } else {
                others++
            }
        }
        val parts = mutableListOf<String>()
        if (bulkIn > 0) parts.add("${bulkIn}xbulkIn")
        if (bulkOut > 0) parts.add("${bulkOut}xbulkOut")
        if (others > 0) parts.add("$others other")
        return if (parts.isEmpty()) "(no endpoints)" else "(${parts.joinToString("+")})"
    }
}
