package ru.mishanikolaev.ladya.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.mishanikolaev.ladya.MainActivity
import ru.mishanikolaev.ladya.R

object AppNotificationManager {

    private const val CALL_CHANNEL_ID = "ladya_calls"
    private const val MESSAGE_CHANNEL_ID = "ladya_messages"
    private const val INCOMING_CALL_NOTIFICATION_ID = 4101
    private const val MESSAGE_NOTIFICATION_ID = 4102

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val callChannel = NotificationChannel(
            CALL_CHANNEL_ID,
            "Вызовы Ладья",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Входящие вызовы и активные звонки"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val messageChannel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            "Сообщения Ладья",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Новые сообщения"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }

        manager.createNotificationChannel(callChannel)
        manager.createNotificationChannel(messageChannel)
    }

    fun showIncomingCallNotification(context: Context, callerTitle: String) {
        ensureChannels(context)
        if (!canPostNotifications(context)) return

        val contentIntent = PendingIntent.getActivity(
            context,
            201,
            MainActivity.createOpenCallIntent(context, fromNotification = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = PendingIntent.getActivity(
            context,
            202,
            MainActivity.createIncomingCallFullscreenIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Входящий вызов")
            .setContentText("$callerTitle звонит Вам")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .build()

        NotificationManagerCompat.from(context).notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    fun showMessageNotification(context: Context, senderTitle: String, messageText: String) {
        ensureChannels(context)
        if (!canPostNotifications(context)) return

        val contentIntent = PendingIntent.getActivity(
            context,
            301,
            MainActivity.createOpenChatIntent(context, fromNotification = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(senderTitle.ifBlank { "Новое сообщение" })
            .setContentText(messageText.ifBlank { "Откройте чат, чтобы посмотреть сообщение" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText.ifBlank { "Откройте чат, чтобы посмотреть сообщение" }))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        NotificationManagerCompat.from(context).notify(MESSAGE_NOTIFICATION_ID, notification)
    }

    fun cancelIncomingCallNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    fun cancelMessageNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(MESSAGE_NOTIFICATION_ID)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
