package com.lenid.zoh.connection.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.lenid.zoh.utils.AppLog

/**
 * USB permission without the system dialog, where the firmware lets us grant it ourselves.
 *
 * Zeekr's head unit runs AOSP test-keys, so a build signed with the AOSP platform key holds
 * MANAGE_USB and may call UsbManager.grantPermission (a system API) for its own uid. A phone
 * then needs no "Allow" at all, though it comes up twice: once as itself and once more after the
 * switch to accessory mode. Anywhere else the permission is missing and the dialog is asked as
 * before, so every caller goes through [requestPermission] and keeps its own PendingIntent flow.
 */
object UsbSelfGrant {

    private const val MANAGE_USB = "android.permission.MANAGE_USB"

    /** Grants [device] to this app without asking; true when the app now holds it. */
    fun grant(context: Context, manager: UsbManager, device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true
        if (context.checkSelfPermission(MANAGE_USB) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            UsbManager::class.java.getMethod("grantPermission", UsbDevice::class.java).invoke(manager, device)
            manager.hasPermission(device).also {
                if (it) AppLog.i("UsbSelfGrant: granted ${UsbDeviceCompat(device).uniqueName} without asking")
            }
        } catch (e: Throwable) {
            AppLog.w("UsbSelfGrant: could not grant ${UsbDeviceCompat(device).uniqueName}: ${e.cause ?: e}")
            false
        }
    }

    /**
     * [UsbManager.requestPermission], answered at once when [grant] succeeds: [intent] is sent with
     * the same extras the system's answer carries, so the caller cannot tell the difference.
     */
    fun requestPermission(context: Context, manager: UsbManager, device: UsbDevice, intent: PendingIntent) {
        if (grant(context, manager, device)) {
            try {
                intent.send(context, 0, Intent()
                    .putExtra(UsbManager.EXTRA_DEVICE, device)
                    .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true))
                return
            } catch (e: PendingIntent.CanceledException) {
                AppLog.w("UsbSelfGrant: permission answer not delivered, asking instead: $e")
            }
        }
        manager.requestPermission(device, intent)
    }
}
