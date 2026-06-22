package com.example.autoclock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autoclock.data.model.ClockAction
import com.example.autoclock.util.NotificationUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自动打卡无障碍服务
 *
 * 采用轮询式状态机设计：
 * 1. ClockAlarmReceiver 设置 pendingTaskId/pendingAction 后调用 onTaskStart()
 * 2. 服务启动轮询，每2秒检查当前页面
 * 3. 按优先级尝试：打卡按钮 > 考勤入口 > 工作台tab
 * 4. 最多重试15次（30秒），超时发送失败通知
 *
 * 钉钉打卡流程：
 * 消息页 → 点"工作"tab → 工作台页 → 点"考勤打卡" → 考勤页 → 点"上班/下班打卡" → 完成
 */
class AutoClockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClockA11y"

        @Volatile
        var pendingTaskId: Long = -1L

        @Volatile
        var pendingAction: ClockAction? = null

        var instance: AutoClockAccessibilityService? = null
            private set

        const val DINGTALK_PACKAGE = "com.alibaba.android.rimet"

        // 工作台 tab 关键词（钉钉底部导航栏）
        private val WORK_TAB_KEYWORDS = listOf("工作", "工作台")

        // 考勤打卡入口关键词（工作台页面的应用图标）
        private val ATTENDANCE_ENTRY_KEYWORDS = listOf("考勤打卡", "考勤", "打卡")

        // 上班打卡按钮关键词
        private val CLOCK_IN_KEYWORDS = listOf("上班打卡", "上班签到", "签到打卡", "上班", "签到")

        // 下班打卡按钮关键词
        private val CLOCK_OUT_KEYWORDS = listOf("下班打卡", "下班签退", "签退打卡", "下班", "签退")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var hasClickedClockButton = false
    private var hasNavigatedToWorkTab = false
    private var hasNavigatedToAttendance = false
    private var retryCount = 0
    private val maxRetries = 15
    private val pollInterval = 2000L
    private val initialDelay = 3000L
    private val successDelay = 3000L

    /**
     * 轮询 Runnable：每2秒检查当前页面，按优先级尝试点击
     */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (pendingTaskId == -1L || pendingAction == null) {
                Log.d(TAG, "pollRunnable: 无待执行任务，停止轮询")
                return
            }
            if (hasClickedClockButton) {
                Log.d(TAG, "pollRunnable: 已点击打卡按钮，停止轮询")
                return
            }
            if (retryCount >= maxRetries) {
                Log.w(TAG, "pollRunnable: 重试${retryCount}次仍未完成，超时")
                onClockFail("超时未找到打卡按钮，请检查钉钉页面")
                return
            }

            retryCount++
            Log.d(TAG, "pollRunnable: 第${retryCount}/${maxRetries}次轮询")

            val root = rootInActiveWindow
            if (root == null) {
                Log.d(TAG, "pollRunnable: rootInActiveWindow 为空，等待下次")
                handler.postDelayed(this, pollInterval)
                return
            }

            try {
                executeClockFlow(root)
            } catch (e: Exception) {
                Log.e(TAG, "pollRunnable: 执行异常", e)
            } finally {
                root.recycle()
            }

            if (!hasClickedClockButton) {
                handler.postDelayed(this, pollInterval)
            }
        }
    }

    /**
     * 被 ClockAlarmReceiver 调用，通知服务开始新的打卡任务
     * 重置内部导航标志，启动轮询定时器
     */
    fun onTaskStart() {
        Log.i(TAG, "onTaskStart: 开始打卡流程, action=$pendingAction")
        hasClickedClockButton = false
        hasNavigatedToWorkTab = false
        hasNavigatedToAttendance = false
        retryCount = 0
        handler.removeCallbacks(pollRunnable)
        // 延迟3秒后开始轮询，等钉钉完全启动
        handler.postDelayed(pollRunnable, initialDelay)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "onServiceConnected: 无障碍服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacks(pollRunnable)
        Log.i(TAG, "onUnbind: 无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (pendingTaskId == -1L || pendingAction == null) return
        if (hasClickedClockButton) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName != DINGTALK_PACKAGE) return

        // 收到钉钉窗口事件时，提前触发一次轮询（不打断已有的定时轮询）
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handler.removeCallbacks(pollRunnable)
                handler.post(pollRunnable)
            }
        }
    }

    /**
     * 执行打卡流程 - 按优先级尝试
     *
     * 优先级：打卡按钮 > 考勤入口 > 工作台tab
     * 这样即使已经到了考勤页面，也不会重复点工作台
     */
    private fun executeClockFlow(root: AccessibilityNodeInfo) {
        // 1. 最高优先级：尝试直接点击打卡按钮
        if (tryClickClockButton(root)) return

        // 2. 尝试点击考勤打卡入口（如果还没进入考勤页）
        if (!hasNavigatedToAttendance && tryClickAttendanceEntry(root)) {
            hasNavigatedToAttendance = true
            Log.i(TAG, "executeClockFlow: 已点击考勤入口，等待考勤页加载")
            return
        }

        // 3. 尝试点击工作台 tab（如果还没进入工作台）
        if (!hasNavigatedToWorkTab && tryClickWorkTab(root)) {
            hasNavigatedToWorkTab = true
            Log.i(TAG, "executeClockFlow: 已点击工作台tab，等待页面加载")
            return
        }

        // 4. 如果已经点了考勤入口但还没找到打卡按钮，可能是页面还在加载
        if (hasNavigatedToAttendance) {
            Log.d(TAG, "executeClockFlow: 已进入考勤页，等待打卡按钮出现...")
        } else if (hasNavigatedToWorkTab) {
            Log.d(TAG, "executeClockFlow: 已进入工作台，等待考勤入口出现...")
        } else {
            Log.d(TAG, "executeClockFlow: 等待钉钉首页加载...")
        }
    }

    /**
     * 尝试点击底部"工作"/"工作台"tab
     */
    private fun tryClickWorkTab(root: AccessibilityNodeInfo): Boolean {
        for (keyword in WORK_TAB_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                try {
                    val clickTarget = findClickableParent(node, 8) ?: node
                    if (clickTarget.isClickable || clickTarget.isEnabled) {
                        // 排除：如果这个节点文字也包含"考勤打卡"等，说明不是tab
                        val nodeText = node.text?.toString() ?: ""
                        if (ATTENDANCE_ENTRY_KEYWORDS.any { nodeText.contains(it) }) {
                            continue
                        }
                        clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "tryClickWorkTab: 点击了 [$keyword] tab")
                        return true
                    }
                } finally {
                    node.recycle()
                }
            }
        }
        return false
    }

    /**
     * 尝试点击"考勤打卡"入口图标
     */
    private fun tryClickAttendanceEntry(root: AccessibilityNodeInfo): Boolean {
        for (keyword in ATTENDANCE_ENTRY_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                try {
                    val nodeText = node.text?.toString() ?: ""
                    // 排除打卡按钮本身（"上班打卡"包含"打卡"，但不是入口）
                    if (CLOCK_IN_KEYWORDS.any { nodeText.contains(it) } ||
                        CLOCK_OUT_KEYWORDS.any { nodeText.contains(it) }
                    ) {
                        continue
                    }

                    val clickTarget = findClickableParent(node, 8) ?: node
                    if (clickTarget.isClickable || clickTarget.isEnabled) {
                        clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "tryClickAttendanceEntry: 点击了 [$keyword] 入口")
                        return true
                    }
                } finally {
                    node.recycle()
                }
            }
        }
        return false
    }

    /**
     * 尝试点击打卡按钮（上班/下班）
     */
    private fun tryClickClockButton(root: AccessibilityNodeInfo): Boolean {
        val keywords = when (pendingAction) {
            ClockAction.CLOCK_IN -> CLOCK_IN_KEYWORDS
            ClockAction.CLOCK_OUT -> CLOCK_OUT_KEYWORDS
            null -> return false
        }

        val actionLabel = when (pendingAction) {
            ClockAction.CLOCK_IN -> "上班打卡"
            ClockAction.CLOCK_OUT -> "下班打卡"
            null -> return false
        }

        // 方式1：通过文本查找
        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                try {
                    val clickTarget = findClickableParent(node, 10) ?: node
                    if (clickTarget.isClickable || clickTarget.isEnabled) {
                        clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        hasClickedClockButton = true
                        Log.i(TAG, "tryClickClockButton: 成功点击 [$actionLabel] 按钮 (text=$keyword)")
                        handler.postDelayed({ onClockSuccess(actionLabel) }, successDelay)
                        return true
                    }
                } finally {
                    node.recycle()
                }
            }
        }

        // 方式2：通过 contentDescription 遍历查找
        if (tryClickByDescription(root, keywords, actionLabel)) return true

        Log.d(TAG, "tryClickClockButton: 未找到打卡按钮，关键词=$keywords")
        return false
    }

    /**
     * 通过 contentDescription 遍历节点树查找并点击
     */
    private fun tryClickByDescription(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        actionLabel: String
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            if (keywords.any { desc.contains(it, true) || text.contains(it, true) }) {
                val clickTarget = findClickableParent(node, 10) ?: node
                if (clickTarget.isClickable) {
                    clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    hasClickedClockButton = true
                    Log.i(TAG, "tryClickByDescription: 成功点击 [$actionLabel] desc=$desc")
                    handler.postDelayed({ onClockSuccess(actionLabel) }, successDelay)
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    /**
     * 向上查找可点击的父节点
     * @param maxDepth 最大向上查找层数
     */
    private fun findClickableParent(
        node: AccessibilityNodeInfo,
        maxDepth: Int
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * 打卡成功：发送通知 + 回桌面 + 重置状态
     */
    private fun onClockSuccess(actionLabel: String) {
        Log.i(TAG, "onClockSuccess: [$actionLabel] 打卡成功!")
        serviceScope.launch {
            NotificationUtil.sendClockResultNotification(
                this@AutoClockAccessibilityService,
                actionLabel,
                true,
                "打卡成功!"
            )
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 500)
        }
        resetState()
    }

    /**
     * 打卡失败：发送失败通知 + 回桌面 + 重置状态
     */
    private fun onClockFail(reason: String) {
        val actionLabel = when (pendingAction) {
            ClockAction.CLOCK_IN -> "上班打卡"
            ClockAction.CLOCK_OUT -> "下班打卡"
            null -> "打卡"
        }
        Log.w(TAG, "onClockFail: [$actionLabel] $reason")
        serviceScope.launch {
            NotificationUtil.sendClockResultNotification(
                this@AutoClockAccessibilityService,
                actionLabel,
                false,
                reason
            )
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 500)
        }
        resetState()
    }

    /**
     * 重置全部状态，准备下次执行
     */
    fun resetState() {
        pendingTaskId = -1L
        pendingAction = null
        hasClickedClockButton = false
        hasNavigatedToWorkTab = false
        hasNavigatedToAttendance = false
        retryCount = 0
        handler.removeCallbacks(pollRunnable)
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt: 无障碍服务被中断")
        resetState()
    }
}
