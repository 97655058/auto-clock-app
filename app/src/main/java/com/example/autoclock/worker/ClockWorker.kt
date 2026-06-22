package com.example.autoclock.worker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.autoclock.data.ClockTaskRepository
import com.example.autoclock.service.AutoClockAccessibilityService
import com.example.autoclock.util.AccessibilityUtil
import com.example.autoclock.util.NotificationUtil
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager Worker：执行实际的自动打卡流程
 *
 * 1. 从数据库查询对应的打卡任务
 * 2. 检查无障碍服务是否已开启
 * 3. 启动钉钉 App 并通知无障碍服务执行打卡
 * 4. 记录打卡结果并发送通知
 */
@HiltWorker
class ClockWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ClockTaskRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TASK_ID = "task_id"
        private const val TAG = "ClockWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId == -1L) {
            Log.e(TAG, "doWork: 无效的任务 ID")
            return@withContext Result.failure()
        }

        val task = repository.getTaskById(taskId)
        if (task == null) {
            Log.e(TAG, "doWork: 未找到任务 ID=$taskId")
            return@withContext Result.failure()
        }

        if (!task.enabled) {
            Log.i(TAG, "doWork: 任务已禁用，跳过 taskId=$taskId")
            return@withContext Result.success()
        }

        Log.i(TAG, "doWork: 开始执行打卡任务 [${task.name}]")

        // 1. 检查无障碍服务是否运行
        if (!AccessibilityUtil.isAccessibilityServiceEnabled(context)) {
            val msg = "无障碍服务未开启，无法执行打卡"
            Log.w(TAG, msg)
            repository.updateLastResult(taskId, "失败: $msg")
            NotificationUtil.sendClockResultNotification(context, task.name, false, msg)
            return@withContext Result.failure()
        }

        // 2. 启动钉钉并通知无障碍服务执行打卡
        try {
            // 将任务信息发送给无障碍服务
            AutoClockAccessibilityService.pendingTaskId = taskId
            AutoClockAccessibilityService.pendingAction = task.clockAction

            // 启动钉钉 App
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage("com.alibaba.android.rimet")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.i(TAG, "doWork: 钉钉已启动，等待无障碍服务执行打卡")
                // 实际打卡操作在 AccessibilityService 中异步完成
                Result.success()
            } else {
                val msg = "未安装钉钉 App"
                Log.e(TAG, "doWork: $msg")
                repository.updateLastResult(taskId, "失败: $msg")
                NotificationUtil.sendClockResultNotification(context, task.name, false, msg)
                Result.failure()
            }
        } catch (e: Exception) {
            val msg = e.message ?: "未知错误"
            Log.e(TAG, "doWork: 打卡异常: $msg", e)
            repository.updateLastResult(taskId, "失败: $msg")
            NotificationUtil.sendClockResultNotification(context, task.name, false, msg)
            Result.failure()
        }
    }
}
