package com.securecall.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device-admin receiver used for exactly one thing: while active, Android requires a user to go
 * through Settings > Security > Device admin apps > SecureCall > Deactivate before "Uninstall"
 * works. That extra screen is what buys time in an emergency (e.g. a child running off with the
 * phone) — it is a delay, not a lock. No other admin capability is requested (see
 * res/xml/device_admin.xml): no password policy, no wipe, no app/camera restrictions. Android
 * always lets whoever holds the phone deactivate it through that Settings path; no app, this one
 * included, can remove or hide that path.
 */
class ChildDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "تم تفعيل حماية إلغاء التثبيت لوضع الطفل", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "تم إيقاف حماية إلغاء التثبيت", Toast.LENGTH_SHORT).show()
    }
}
