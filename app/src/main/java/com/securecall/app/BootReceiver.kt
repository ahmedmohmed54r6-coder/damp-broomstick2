package com.securecall.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val code = Store.prefs(ctx).getString("pcode", "") ?: ""
        if (code.isNotBlank()) CallListenService.start(ctx)
    }
}
