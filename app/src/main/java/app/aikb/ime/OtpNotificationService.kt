package app.aikb.ime

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.aikb.ime.engine.OtpStore
import app.aikb.ime.keyboard.KeyboardPrefs

/**
 * Strictly offline notification listener dedicated solely to extracting 4-8 digit verification
 * codes (`来自「信息」 2046`) when `otpAutoFill` is enabled by the user.
 *
 * Extracted codes live exclusively in volatile memory (`OtpStore`) for 90 seconds and are never
 * written to disk or transmitted anywhere (the app has zero network permissions).
 */
class OtpNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val prefs = getSharedPreferences(ImeService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KeyboardPrefs.KEY_OTP_AUTOFILL, true)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (text.isNullOrBlank() && title.isNullOrBlank()) return

        val appLabel = runCatching {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrNull()

        OtpStore.onCandidateText(title, text, appLabel)
    }

    companion object {
        fun isNotificationAccessEnabled(context: Context): Boolean = runCatching {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val cn = ComponentName(context, OtpNotificationService::class.java).flattenToString()
            cn in flat
        }.getOrDefault(false)
    }
}
