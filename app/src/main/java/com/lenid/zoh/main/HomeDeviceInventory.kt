package com.lenid.zoh.main

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.lenid.zoh.App
import com.lenid.zoh.R
import com.lenid.zoh.connection.usb.UsbDeviceCompat
import com.lenid.zoh.dongle.DongleService
import com.lenid.zoh.utils.BluetoothHelper

internal object HomeDeviceInventory {
    data class Snapshot(val devices: List<HomeDevice>, val bluetoothUnavailable: Boolean)

    /** Read-only enumeration; no radio scans, USB opens, pairing or session changes. */
    @SuppressLint("MissingPermission")
    fun read(context: Context): Snapshot {
        val rows = mutableListOf<HomeDevice>()
        val usb = UsbDeviceCompat.usbManager(context)?.deviceList?.values.orEmpty()
        usb.forEach { device ->
            val kind = when {
                DongleService.isDongle(device) -> HomeDevice.Kind.DONGLE
                device.vendorId == 0x05ac && Build.VERSION.SDK_INT >= 24 -> HomeDevice.Kind.CARPLAY_USB
                UsbDeviceCompat.isConnectable(context, device) -> HomeDevice.Kind.AA_USB
                else -> null
            } ?: return@forEach
            val name = if (kind == HomeDevice.Kind.DONGLE) "Carlinkit / AutoKit"
                else if (Build.VERSION.SDK_INT >= 21) runCatching { device.productName }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: UsbDeviceCompat.getUniqueName(device)
                else UsbDeviceCompat.getUniqueName(device)
            rows += HomeDevice(device.deviceName, name, kind)
        }
        var unavailable = false
        try {
            val adapter = BluetoothHelper.getBluetoothAdapter(context)
            if (adapter == null || !adapter.isEnabled) unavailable = true
            else {
                val settings = App.provide(context).settings
                val phones = BluetoothHelper.driverCandidates(context, settings.nativePreferredDeviceMac,
                    settings.lastConnectedNativeMac).offered.map { it.address }.toSet()
                adapter.bondedDevices.orEmpty().forEach { device ->
                    val name = device.name.orEmpty()
                    val iphone = name.contains("iPhone", ignoreCase = true)
                    if (iphone && Build.VERSION.SDK_INT < 24) return@forEach
                    if (iphone || device.address in phones) rows += HomeDevice(device.address,
                        name.ifBlank { context.getString(R.string.home_device_unnamed) },
                        if (iphone) HomeDevice.Kind.CARPLAY_WIRELESS else HomeDevice.Kind.AA_WIRELESS)
                }
            }
        } catch (_: SecurityException) { unavailable = true }
        return Snapshot(rows.distinctBy { it.key }.sortedWith(compareBy<HomeDevice> { it.kind.ordinal }
            .thenBy { it.name.lowercase() }), unavailable)
    }
}
