package com.hangly.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.bootStart && prefs.enabled && Settings.canDrawOverlays(context)) {
            OverlayService.start(context)
        }
    }
}
