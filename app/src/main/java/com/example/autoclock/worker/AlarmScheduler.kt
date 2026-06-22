package com.example.autoclock.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.autoclock.data.model.ClockTask
import com.example.autoclock.data.model.isDayEnabled
import java.util.Calendar

/**
 * AlarmManager 调度器 - 替代 WorkManager
 *
 * 使用 AlarmManager.setAlarmClock() 做精确闹钟调度：
 * - 不受 Doze 模式限制，锁屏状态也能唤醒
 * - 国产 ROM 上比 WorkManager 可靠得多
 * - 支持工作日判断，只在指定工作日触发
 * - 闹钟触发后自动设置下一天的闹钟（日循环）
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_ACTION = "clock_action"
    const val EXTRA_TASK_NAME = "task_name"

    /**
     * 为任务创建或更新闹钟调度
     */
    fun scheduleTask(context: Context, task: ClockTask) {
        if (!task.enabled) {
            cancelTask(context, task.id)
            return
        }

        when (task.triggerType) {
            com.example.autoclock.data.model.TriggerType.SCHEDULED -> scheduleTimedTask(context, task)
            com.example.autoclock.data.model.TriggerType.WIFI -> {
                Log.i(TAG, "scheduleTask: Wi-Fi 触发任务不使用闹钟 taskId=${task.id}")
            }
        }
    }

    /**
     * 设置精确闹钟
     */
    private fun scheduleTimedTask(context: Context, task: ClockTask) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val triggerTime = calculateNextTriggerTime(task)
        val pendingIntent = createPendingIntent(context, task)

        // setAlarmClock - 最高优先级，不受 Doze 限制
        val alarmInfo = AlarmManager.AlarmClockInfo(triggerTime, null)
        alarmManager.setAlarmClock(alarmInfo, pendingIntent)

        val cal = Calendar.getInstance().apply { timeInMillis = triggerTime }
        Log.i(
            TAG,
            "scheduleTimedTask: 已调度 [${task.name}] " +
                    "下次触发: ${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)} " +
                    "${cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')}:" +
                    "${cal.get(Calendar.MINUTE).toString().padStart(2, '0')}"
        )
    }

    /**
     * 取消指定任务的闹钟
     */
    fun cancelTask(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ClockAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "cancelTask: 已取消闹钟 taskId=$taskId")
    }

    /**
     * 立即执行一次任务（用于测试）
     */
    fun runTaskImmediately(context: Context, task: ClockTask) {
        val intent = Intent(context, ClockAlarmReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_ACTION, task.clockAction.name)
            putExtra(EXTRA_TASK_NAME, task.name)
        }
        context.sendBroadcast(intent)
        Log.i(TAG, "runTaskImmediately: 立即触发 [${task.name}]")
    }

    /**
     * 创建 PendingIntent
     */
    private fun createPendingIntent(context: Context, task: ClockTask): PendingIntent {
        val intent = Intent(context, ClockAlarmReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_ACTION, task.clockAction.name)
            putExtra(EXTRA_TASK_NAME, task.name)
        }
        return PendingIntent.getBroadcast(
            context,
            task.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 计算下次触发时间（考虑工作日）
     *
     * @return 下次触发的毫秒时间戳
     */
    private fun calculateNextTriggerTime(task: ClockTask): Long {
        val now = Calendar.getInstance()

        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, task.triggerHour)
            set(Calendar.MINUTE, task.triggerMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 如果今天的时间已过，从明天开始找
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }

        // 找到下一个工作日（最多检查7天）
        var daysChecked = 0
        while (daysChecked < 7) {
            // Calendar.DAY_OF_WEEK: 1=周日, 2=周一, ..., 7=周六
            // workdays bit0=周一, bit1=周二, ..., bit6=周日
            val dayOfWeek = target.get(Calendar.DAY_OF_WEEK)
            val maskIndex = (dayOfWeek - 2 + 7) % 7

            if (task.workdays.isDayEnabled(maskIndex)) {
                Log.d(TAG, "calculateNextTriggerTime: 找到工作日, maskIndex=$maskIndex, " +
                        "时间=${target.time}")
                return target.timeInMillis
            }
            target.add(Calendar.DAY_OF_MONTH, 1)
            daysChecked++
        }

        // 7天内没找到工作日（不应该发生），返回明天
        Log.w(TAG, "calculateNextTriggerTime: 7天内无工作日，使用明天")
        return target.timeInMillis
    }
}
