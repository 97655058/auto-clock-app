package com.example.autoclock.worker

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.example.autoclock.data.model.ClockAction
import com.example.autoclock.service.AutoClockAccessibilityService
import com.example.autoclock.util.AccessibilityUtil
import com.example.autoclock.util.NotificationUtil
import java.io.DataOutputStream

/**
 * 闹钟广播接收器
 *
 * 当 AlarmManager 闹钟触发时，此 Receiver 被系统调用：
 * 1. 检查无障碍服务是否运行
 * 2. 点亮屏幕，尝试解锁
 * 3. 通过 Intent 参数传递任务信息（不依赖静态变量）
 * 4. 启动钉钉 App
 * 5. 无障碍服务自动执行打卡操作
 */
class ClockAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ClockAlarmReceiver"
        private const val DINGTALK_PACKAGE = "com.alibaba.android.rimet"

        /**
         * 唤醒屏幕：点亮屏幕 + 尝试解锁
         */
        fun wakeUpAndUnlock(context: Context) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            val isKeyguardLocked = keyguardManager.isDeviceLocked

            Log.i(TAG, "wakeUpAndUnlock: keyguardLocked=$isKeyguardLocked")

            // ═══════════════════════════════════════════════════════════
            // 第一步：获取唤醒锁并点亮屏幕
            // ═══════════════════════════════════════════════════════════
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "AutoClock:wakelock"
            )
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(3000L) // 3秒后释放，避免一直占用

            // 点亮屏幕：模拟电源键按下
            val now = System.currentTimeMillis()
            val powerKeyEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER, 0)
            context.dispatchKeyEvent(powerKeyEvent)

            Log.i(TAG, "wakeUp: 已请求点亮屏幕 + 释放唤醒锁")

            // ═══════════════════════════════════════════════════════════
            // 第二步：尝试解锁屏幕
            // ═══════════════════════════════════════════════════════════
            if (isKeyguardLocked) {
                unlockScreenWithSwipe(context, powerManager)
            } else {
                Log.i(TAG, "wakeUp: 屏幕未锁定，无需解锁")
            }
        }

        /**
         * 解锁屏幕：尝试模拟上滑解锁手势
         *
         * Android 4.0+ 的锁屏滑动解锁手势：从屏幕底部向上滑动
         */
        private fun unlockScreenWithSwipe(context: Context, powerManager: PowerManager) {
            Log.i(TAG, "unlockScreen: 尝试模拟上滑解锁...")

            val display = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            val screenWidth = display.width
            val screenHeight = display.height

            val startX = screenWidth / 2           // 屏幕中心 x
            val startY = screenHeight - 100        // 底部
            val endY = 100                          // 顶部

            // ═══════════════════════════════════════════════════════════
            // 方案1：尝试使用 InputManager 注入触摸事件（需要系统签名）
            // ═══════════════════════════════════════════════════════════
            try {
                val inputManager = Class.forName("android.hardware.input.InputManager")
                    .getMethod("getInstance")
                    .invoke(null)

                val injectMethod = inputManager.javaClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.java
                )

                // 触摸事件参数：INJECT_INPUT_EVENT_MODE_ASYNC = 0
                val mode = 0

                // ACTION_DOWN
                val down = android.view.MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    android.view.MotionEvent.ACTION_DOWN,
                    startX.toFloat(),
                    startY.toFloat(),
                    0
                )
                injectMethod.invoke(inputManager, down, mode)

                // ACTION_MOVE
                val move = android.view.MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    android.view.MotionEvent.ACTION_MOVE,
                    startX.toFloat(),
                    (startY + endY) / 2f,
                    0
                )
                injectMethod.invoke(inputManager, move, mode)

                // ACTION_UP
                val up = android.view.MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    android.view.MotionEvent.ACTION_UP,
                    startX.toFloat(),
                    endY.toFloat(),
                    0
                )
                injectMethod.invoke(inputManager, up, mode)

                Log.i(TAG, "unlockScreen: ✅ 使用 InputManager 注入成功")
                return
            } catch (e: Exception) {
                Log.w(TAG, "unlockScreen: InputManager 注入失败（无系统签名）", e)
            }

            // ═══════════════════════════════════════════════════════════
            // 方案2：使用adb shell input命令（需要root权限）
            // ═══════════════════════════════════════════════════════════
            try {
                val process = Runtime.getRuntime().exec("su")
                val outputStream = DataOutputStream(process.outputStream)
                outputStream.writeBytes("input swipe $startX $startY $startX $endY 500\n")
                outputStream.flush()
                outputStream.writeBytes("exit\n")
                outputStream.flush()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    Log.i(TAG, "unlockScreen: ✅ 使用 su 命令解锁成功")
                    return
                }
                Log.w(TAG, "unlockScreen: su 命令失败，退出码=$exitCode")
            } catch (e: Exception) {
                Log.w(TAG, "unlockScreen: su 命令失败（无root）", e)
            }

            // ═══════════════════════════════════════════════════════════
            // 方案3：发送HOME键 + 绕过锁屏（部分机型有效）
            // ═══════════════════════════════════════════════════════════
            try {
                val flags = 0
                powerManager.wakeLock.takeIf { it != null }?.let {
                    // 不操作，已经在前面包里acquire过
                }

                // 发送HOME键，尝试绕过锁屏直接到桌面
                val homeIntent = Intent(Intent.ACTION_HOME).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(homeIntent)

                Log.i(TAG, "unlockScreen: 发送HOME键尝试绕过锁屏")
            } catch (e: Exception) {
                Log.w(TAG, "unlockScreen: HOME键方案失败", e)
            }

            Log.w(TAG, "unlockScreen: 所有解锁方案均需要额外权限，请手动上滑解锁后自动启动钉钉")
        }
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

        // ═══════════════════════════════════════════════════════════
        // 第一步：唤醒屏幕 + 尝试解锁
        // ═══════════════════════════════════════════════════════════
        wakeUpAndUnlock(context)

        // 发送解锁提示通知
        NotificationUtil.sendUnlockHintNotification(context, taskName)

        // 短暂延迟，等待屏幕点亮 + 解锁动画
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            executeClockTask(context, taskId, action, taskName)
        }, 1500L)
    }

    /**
     * 执行打卡任务（唤醒屏幕后调用）
     */
    private fun executeClockTask(context: Context, taskId: Long, action: ClockAction, taskName: String) {
        // 1. 检查无障碍服务是否已开启
        if (!AccessibilityUtil.isAccessibilityServiceEnabled(context)) {
            val msg = "无障碍服务未开启，无法自动打卡"
            Log.w(TAG, msg)
            NotificationUtil.sendClockResultNotification(context, taskName, false, msg)
            return
        }

        // 2. 通过静态变量传递任务信息给无障碍服务
        AutoClockAccessibilityService.pendingTaskId = taskId
        AutoClockAccessibilityService.pendingAction = action
        // 通知服务开始打卡流程（重置内部标志，启动轮询）
        AutoClockAccessibilityService.instance?.onTaskStart()

        // 3. 启动钉钉 App
        try {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(DINGTALK_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
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
