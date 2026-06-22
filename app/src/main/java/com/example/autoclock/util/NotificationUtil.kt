package com.example.autoclock.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.autoclock.R
import com.example.autoclock.ui.main.MainActivity

object NotificationUtil {

    const val CHANNEL_ID = "auto_clock_channel"
    const val CHANNEL_NAME = "自动打卡通知"
    private const val NOTIFICATION_ID_BASE = 1000

    /**
     * 创建通知 Channel（需在 App 启动时调用）
     */
    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "自动打卡执行结果通知"
            enableVibration(true)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    /**
     * 发送打卡结果通知
     */
    fun sendClockResultNotification(
        context: Context,
        taskName: String,
        success: Boolean,
        message: String
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = if (success) android.R.drawable.ic_dialog_info
        else android.R.drawable.ic_dialog_alert

        val title = if (success) "✅ 打卡成功" else "❌ 打卡失败"
        val text = "[$taskName] $message"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID_BASE + taskName.hashCode(), notification)
    }
}
