package com.example.autoclock.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.autoclock.data.model.ClockTask
import com.example.autoclock.data.model.TriggerType
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager 调度器
 *
 * 负责为每个打卡任务设置或取消 WorkManager 的定时调度。
 * - SCHEDULED 类型：每天在指定时间（仅指定工作日）触发
 * - WIFI 类型：由 WifiReceiver 广播触发，不通过 WorkManager 调度
 */
object WorkManagerScheduler {

    private const val TAG = "WorkManagerScheduler"

    /**
     * 为任务创建或更新调度
     */
    fun scheduleTask(context: Context, task: ClockTask) {
        if (!task.enabled) {
            cancelTask(context, task.id)
            return
        }

        when (task.triggerType) {
            TriggerType.SCHEDULED -> scheduleTimedTask(context, task)
            TriggerType.WIFI -> {
                // Wi-Fi 触发不使用 WorkManager，由 WifiReceiver 处理
                Log.i(TAG, "scheduleTask: Wi-Fi 触发任务不需要 WorkManager 调度 taskId=${task.id}")
            }
        }
    }

    /**
     * 为定时任务设置 WorkManager 每日周期调度
     */
    private fun scheduleTimedTask(context: Context, task: ClockTask) {
        val delayMillis = calculateNextTriggerDelay(task)
        if (delayMillis < 0) {
            Log.i(TAG, "scheduleTimedTask: 今天不需要触发 taskId=${task.id}")
            // 设置到明天相同时间（让 WorkManager 一直保持）
        }

        val inputData = workDataOf(ClockWorker.KEY_TASK_ID to task.id)

        // 使用 PeriodicWorkRequest（24小时周期），首次延迟精确定向到目标时间
        val actualDelay = if (delayMillis >= 0) delayMillis else delayMillis + TimeUnit.DAYS.toMillis(1)

        val workRequest = PeriodicWorkRequestBuilder<ClockWorker>(24, TimeUnit.HOURS)
            .setInputData(inputData)
            .setInitialDelay(actualDelay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .addTag("clock_task_${task.id}")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            uniqueWorkName(task.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        Log.i(
            TAG,
            "scheduleTimedTask: 已调度任务 [${task.name}] " +
                    "时间=${task.triggerHour}:${task.triggerMinute.toString().padStart(2, '0')} " +
                    "初始延迟=${actualDelay / 1000}秒"
        )
    }

    /**
     * 计算距离下次触发时间的毫秒数（可能为负，说明今日已过该时间）
     */
    private fun calculateNextTriggerDelay(task: ClockTask): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, task.triggerHour)
            set(Calendar.MINUTE, task.triggerMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return target.timeInMillis - now.timeInMillis
    }

    /**
     * 取消指定任务的调度
     */
    fun cancelTask(context: Context, taskId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(taskId))
        Log.i(TAG, "cancelTask: 已取消任务调度 taskId=$taskId")
    }

    /**
     * 立即执行一次任务（用于测试）
     */
    fun runTaskImmediately(context: Context, taskId: Long) {
        val inputData = workDataOf(ClockWorker.KEY_TASK_ID to taskId)
        val workRequest = OneTimeWorkRequestBuilder<ClockWorker>()
            .setInputData(inputData)
            .addTag("immediate_task_$taskId")
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
        Log.i(TAG, "runTaskImmediately: 立即执行任务 taskId=$taskId")
    }

    private fun uniqueWorkName(taskId: Long) = "auto_clock_task_$taskId"
}
