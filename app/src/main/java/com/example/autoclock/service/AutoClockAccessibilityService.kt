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
 * 工作原理：
 * 1. 通过 [pendingTaskId] 和 [pendingAction] 静态字段接收来自 [ClockWorker] 的指令
 * 2. 监听钉钉 App 的窗口变化事件，当检测到考勤页面时，自动点击对应按钮
 * 3. 打卡完成后通过 NotificationUtil 发送通知，并退出钉钉回到桌面
 *
 * 钉钉打卡页面特征（基于钉钉 7.x/8.x）：
 * - 窗口类名：com.alibaba.android.rimet 相关 Activity
 * - 上班打卡按钮：contentDescription / text 包含 "上班打卡" 或 ID 含 "clock_in"
 * - 下班打卡按钮：contentDescription / text 包含 "下班打卡" 或 ID 含 "clock_out"
 * - 考勤页面标识：标题包含 "考勤" 或 "打卡"
 */
class AutoClockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClockA11y"

        /** 待执行的任务 ID（由 ClockWorker 写入） */
        @Volatile
        var pendingTaskId: Long = -1L

        /** 待执行的打卡动作 */
        @Volatile
        var pendingAction: ClockAction? = null

        /** 当前服务实例（判断服务是否运行） */
        var instance: AutoClockAccessibilityService? = null
            private set

        // 钉钉包名
        const val DINGTALK_PACKAGE = "com.alibaba.android.rimet"

        // 考勤页面识别关键词
        private val ATTENDANCE_KEYWORDS = listOf("考勤", "打卡", "attendance", "clock")

        // 打卡按钮识别关键词（宽松匹配，适配不同钉钉版本）
        private val CLOCK_IN_KEYWORDS = listOf("上班打卡", "上班", "clock in", "签到")
        private val CLOCK_OUT_KEYWORDS = listOf("下班打卡", "下班", "clock out", "签退")

        // 进入考勤页的入口关键词
        private val ATTENDANCE_ENTRY_KEYWORDS = listOf("考勤打卡", "考勤")
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    /** 防止重复点击 */
    private var hasClickedClockButton = false

    /** 记录是否已经导航进入考勤页 */
    private var hasNavigatedToAttendance = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "onServiceConnected: 无障碍服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "onUnbind: 无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (pendingTaskId == -1L || pendingAction == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName != DINGTALK_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleDingTalkWindow(event)
            }
        }
    }

    private fun handleDingTalkWindow(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        try {
            val windowTitle = event.text?.joinToString() ?: ""
            val className = event.className?.toString() ?: ""

            Log.d(TAG, "handleDingTalkWindow: title=$windowTitle, class=$className")

            // 如果在主界面且还未导航到考勤页，尝试点击考勤入口
            if (!hasNavigatedToAttendance) {
                if (tryNavigateToAttendance(rootNode)) {
                    hasNavigatedToAttendance = true
                    return
                }
            }

            // 检测是否在考勤/打卡页面
            val isAttendancePage = ATTENDANCE_KEYWORDS.any { keyword ->
                windowTitle.contains(keyword, ignoreCase = true) ||
                        className.contains(keyword, ignoreCase = true)
            } || isAttendancePageByNodes(rootNode)

            if (isAttendancePage && !hasClickedClockButton) {
                performClockAction(rootNode)
            }
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 通过节点内容判断是否在考勤打卡页面
     */
    private fun isAttendancePageByNodes(root: AccessibilityNodeInfo): Boolean {
        val keywords = CLOCK_IN_KEYWORDS + CLOCK_OUT_KEYWORDS
        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        return false
    }

    /**
     * 尝试在钉钉主界面找到并点击考勤打卡入口
     */
    private fun tryNavigateToAttendance(root: AccessibilityNodeInfo): Boolean {
        for (keyword in ATTENDANCE_ENTRY_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                try {
                    val clickTarget = findClickableParent(node) ?: node
                    if (clickTarget.isClickable) {
                        clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "tryNavigateToAttendance: 已点击考勤入口 [$keyword]")
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
     * 执行打卡动作：在当前页面查找并点击对应按钮
     */
    private fun performClockAction(root: AccessibilityNodeInfo) {
        val keywords = when (pendingAction) {
            ClockAction.CLOCK_IN -> CLOCK_IN_KEYWORDS
            ClockAction.CLOCK_OUT -> CLOCK_OUT_KEYWORDS
            null -> return
        }

        val actionLabel = when (pendingAction) {
            ClockAction.CLOCK_IN -> "上班打卡"
            ClockAction.CLOCK_OUT -> "下班打卡"
            null -> return
        }

        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                try {
                    val clickTarget = findClickableParent(node) ?: node
                    if (clickTarget.isClickable || clickTarget.isEnabled) {
                        clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        hasClickedClockButton = true
                        Log.i(TAG, "performClockAction: 成功点击[$actionLabel]按钮")

                        // 延迟处理打卡结果并退出
                        handler.postDelayed({
                            onClockSuccess(actionLabel)
                        }, 3000)
                        return
                    }
                } finally {
                    node.recycle()
                }
            }
        }

        // 如果文本匹配不到，尝试通过 contentDescription 匹配
        if (tryClickByDescription(root, keywords, actionLabel)) return

        Log.w(TAG, "performClockAction: 未找到打卡按钮，关键词=$keywords")
    }

    /**
     * 通过 contentDescription 查找并点击节点
     */
    private fun tryClickByDescription(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        actionLabel: String
    ): Boolean {
        // 遍历节点树（深度优先）
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            if (keywords.any { desc.contains(it, true) || text.contains(it, true) }) {
                val clickTarget = findClickableParent(node) ?: node
                if (clickTarget.isClickable) {
                    clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    hasClickedClockButton = true
                    Log.i(TAG, "tryClickByDescription: 成功点击[$actionLabel]按钮 desc=$desc")
                    handler.postDelayed({ onClockSuccess(actionLabel) }, 3000)
                    node.recycle()
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (node != root) node.recycle()
        }
        return false
    }

    /**
     * 向上查找可点击的父节点
     */
    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * 打卡成功回调：通知 + 重置状态 + 退回桌面
     */
    private fun onClockSuccess(actionLabel: String) {
        serviceScope.launch {
            NotificationUtil.sendClockResultNotification(
                this@AutoClockAccessibilityService,
                actionLabel,
                true,
                "打卡成功！"
            )
            // 更新数据库（需要注入 repository，此处通过广播或静态方式通知）
            Log.i(TAG, "onClockSuccess: [$actionLabel] 打卡成功")

            // 退回桌面
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 500)
        }
        // 重置状态
        resetState()
    }

    /**
     * 重置服务状态，准备下次执行
     */
    fun resetState() {
        pendingTaskId = -1L
        pendingAction = null
        hasClickedClockButton = false
        hasNavigatedToAttendance = false
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt: 无障碍服务被中断")
        resetState()
    }
}
