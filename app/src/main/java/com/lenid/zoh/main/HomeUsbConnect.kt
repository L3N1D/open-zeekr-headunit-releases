package com.lenid.zoh.main

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.lenid.zoh.App
import com.lenid.zoh.R
import com.lenid.zoh.aap.AapProjectionActivity
import com.lenid.zoh.aap.AapService
import com.lenid.zoh.connection.usb.UsbAccessoryMode
import com.lenid.zoh.connection.usb.UsbDeviceCompat
import com.lenid.zoh.connection.usb.UsbReceiver
import com.lenid.zoh.connection.usb.UsbSelfGrant
import com.lenid.zoh.utils.Settings
import com.lenid.zoh.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared manual USB action. No changes to permission or AOA switching behavior. */
internal object HomeUsbConnect {
    fun connect(context: Context, settings: Settings, scope: CoroutineScope, device: UsbDeviceCompat, onChanged: () -> Unit = {}) {
        if (settings.isUsbDeviceBlacklisted(device.wrappedDevice)) {
            // The row already says "Blacklisted" and the button used to connect anyway.
            ToastUtils.showToast(context, R.string.blacklisted, Toast.LENGTH_SHORT, force = true)
            return
        }
        AapService.instance?.liftUsbCancel("a USB device was chosen from the list")
        if (App.provide(context).commManager.isConnected) {

            // Already connected -> bring existing projection to front
            val aapIntent = AapProjectionActivity.intent(context).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(aapIntent)
        } else if (device.isInAccessoryMode) {
            // Device is in Accessory Mode but we are NOT connected.
            // Start connection immediately.
            ToastUtils.showToast(context, R.string.android_auto_starting, Toast.LENGTH_SHORT)
            (context as? MainActivity)?.beginAutoConnect(
                "manual USB list (accessory mode)",
                MainActivity.ConnectionUiMode.OVERLAY
            )
            ContextCompat.startForegroundService(context, Intent(context, AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
                putExtra(AapService.EXTRA_USER_REQUESTED, true)
            })
        } else {
            // Standard connection flow
            val usbManager = UsbDeviceCompat.usbManager(context) ?: return
            if (usbManager.hasPermission(device.wrappedDevice)) {
                val usbMode = UsbAccessoryMode(usbManager)
                val useLibusb = settings.useLibusb
                // Eight control transfers at a 1 s timeout each. On the main thread that is
                // an ANR against any device that stops answering rather than refusing.
                scope.launch(Dispatchers.IO) {
                    val switched = usbMode.connectAndSwitch(device.wrappedDevice, useLibusb)
                    withContext(Dispatchers.Main) {
                        if (switched) {
                            ToastUtils.showToast(context, R.string.switching_to_android_auto, Toast.LENGTH_SHORT)
                            (context as? MainActivity)?.beginAutoConnect(
                                "manual USB list (AOA switch)",
                                MainActivity.ConnectionUiMode.OVERLAY
                            )
                        } else {
                            ToastUtils.showToast(context, R.string.switch_failed, Toast.LENGTH_SHORT, force = true)
                        }
                        onChanged()
                    }
                }
            } else {
                ToastUtils.showToast(context, R.string.requesting_usb_permission, Toast.LENGTH_SHORT)
                ContextCompat.startForegroundService(context, Intent(context, AapService::class.java))
                UsbSelfGrant.requestPermission(
                    context, usbManager, device.wrappedDevice,
                    UsbReceiver.createPermissionPendingIntent(context)
                )
            }
        }
    }
}
