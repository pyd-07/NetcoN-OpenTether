package com.opentether.tunnel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import com.opentether.logging.AppLogger
import com.opentether.runtime.TunnelRuntimeHolder
import com.opentether.data.TunnelTransport
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal const val ACTION_USB_PERMISSION = "com.opentether.action.USB_PERMISSION"

internal suspend fun requestUsbAccessoryPermission(
    context: Context,
    usbManager: UsbManager,
    accessory: UsbAccessory,
): Boolean {
    if (usbManager.hasPermission(accessory)) return true

    TunnelRuntimeHolder.onTransportWaiting(
        transport = TunnelTransport.AOA,
        detail = "Accept the USB accessory permission dialog on your phone.",
    )

    return suspendCancellableCoroutine { cont ->
        var receiverRegistered = false

        fun unregister(receiver: BroadcastReceiver) {
            if (!receiverRegistered) return
            receiverRegistered = false
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // Receiver may already have been removed by Android during
                // process/service recreation.
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                unregister(this)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                AppLogger.i("OT/UsbPermission", "USB accessory permission result: granted=$granted")
                if (cont.isActive) cont.resume(granted)
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        receiverRegistered = true

        cont.invokeOnCancellation { unregister(receiver) }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ACTION_USB_PERMISSION.hashCode(),
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        usbManager.requestPermission(accessory, pendingIntent)
    }
}
