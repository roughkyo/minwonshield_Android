package kr.hs.minwonshield

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager

class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                preferences.edit().putBoolean(KEY_WAS_OFFHOOK, true).apply()
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val callWasConnected = preferences.getBoolean(KEY_WAS_OFFHOOK, false)
                preferences.edit().putBoolean(KEY_WAS_OFFHOOK, false).apply()
                if (callWasConnected) {
                    showUploadNotification(context)
                }
            }
        }
    }

    private fun showUploadNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)

        val openPicker = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_PICKER, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openPicker,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val PREFERENCES = "call_state"
        private const val KEY_WAS_OFFHOOK = "was_offhook"
        private const val CHANNEL_ID = "call_upload"
        private const val NOTIFICATION_ID = 2001
    }
}
