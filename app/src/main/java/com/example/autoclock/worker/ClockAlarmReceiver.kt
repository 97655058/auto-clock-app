package com.example.autoclock.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.autoclock.data.model.ClockAction
import com.example.autoclock.service.AutoClockAccessibilityService
import com.example.autoclock.util.AccessibilityUtil
import com.example.autoclock.util.NotificationUtil

/**
 * 闹钟广播接收器
 *
 * 当 AlarmManager 闹钟触发时，此 Receiver 被系统调用：
 * 1. 检查无障碍服务是否运行
 * 2. 通过 Intent 参数传递任务信息（不依赖静态变量）
 * 3. 启动钉钉 App
 * 4. 无障碍服务自动执行打卡操作
 */
class ClockAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ClockAlarmReceiver"
        private const val DINGTALK_PACKAGE = "com.alibaba.android.rimet"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(AlarmScheduler.EXTRA_TASK_ID, -1L)
        val actionName = intent.getStringExtra(AlarmScheduler.EXTRA_ACTION) ?: "CLOCK_IN"
        val taskName = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_NAME) ?: "打卡任务"

        Log.i(TAG, "onReceive: 闹钟触发! taskId=$taskId, action=$actionName, name=$taskName")

        val action = try {
            ClockAction.valueOf(actionName)
        } catch (e: Exception) {
            ClockAction.CLOCK_IN
        }

        // 1. 检查无障碍服务是否已开启
        if (!AccessibilityUtil.isAccessibilityServiceEnabled(context)) {
            val msg = "无障碍服务未开启，无法自动打卡"
            Log.w(TAG, msg)
            NotificationUtil.sendClockResultNotification(context, taskName, false, msg)
            return
        }

        // 2. 通过静态变量传递任务信息给无障碍服务
        //    （虽然用静态变量，但闹钟触发时服务如果已开启就不会被杀）
        AutoClockAccessibilityService.pendingTaskId = taskId
        AutoClockAccessibilityService.pendingAction = action
        AutoClockAccessibilityService.instance?.resetState()

        // 3. 启动钉钉 App
        try {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(DINGTALK_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.i(TAG, "钉钉已启动，等待无障碍服务执行打卡")

                // 发送"正在打卡"通知
                NotificationUtil.sendClockResultNotification(
                    context, taskName, true, "正在自动打卡..."
                )
            } else {
                val msg = "未检测到钉钉应用"
                Log.e(TAG, msg)
                NotificationUtil.sendClockResultNotification(context, taskName, false, msg)
            }
        } catch (e: Exception) {
            val msg = "启动钉钉失败: ${e.message}"
            Log.e(TAG, msg, e)
            NotificationUtil.sendClockResultNotification(context, taskName, false, msg)
        }
    }
}
