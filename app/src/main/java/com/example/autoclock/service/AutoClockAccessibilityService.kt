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
 * 自动打卡无障碍服务 — 基于用户提供的钉钉真实截图重写
 *
 * ═══════════════════════ 钉钉实际界面结构 ═════════════════════════
 *
 * 【消息首页】（DingTalk 启动后默认显示）
 *   顶部导航栏：日历 | 待办 | DING | ☐ **打卡** | 通话   ← 目标1：点这个"打卡"
 *   下方：聊天列表...
 *
 * 【考勤页面】（点击顶部"打卡"后进入）
 *   标题栏：< 公司名 ...
 *   卡片区：上班08:30(✓已打卡) | 下班12:00(✓已打卡) | 上班13:30(✓已打卡)
 *   中间大蓝按钮：**"上班打卡"** / **"下班打卡"**          ← 目标2：点这个完成
 *   底部 tab：打卡 | 统计 | 设置
 *
 * ═════════════════════════ 两步打卡流程 ═════════════════════════
 *
 *  Phase 0 → 等待钉钉启动，rootInActiveWindow 可用
 *  Phase 1 → 在首页找顶部导航栏的"打卡"按钮并点击
 *  Phase 2 → 考勤页加载完毕，找中间的大蓝色"上班打卡"/"下班打卡"按钮并点击
 *  Done    → 发通知、回桌面、重置状态
 *
 * ═════════════════════════ 关键词定义（来自真实截图） ════════════════
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

        // ── 第1步：首页导航栏的"打卡"入口 ──
        // 截图显示：日历 | 待办 | DING | ☐ 打卡 | 通话
        // 这个"打卡"文字在顶部导航栏，点击后跳转到考勤页面
        private val NAV_CLOCK_ENTRY = listOf("打卡")

        // ── 第2步：考勤页面的实际打卡按钮 ──
        // 截图显示：中间大蓝圆形按钮，文字为 "上班打卡" 或 "下班打卡"
        // 注意：底部也有一个"打卡"tab，需要排除（通过位置/上下文区分）
        private val CLOCK_IN_BUTTONS = listOf("上班打卡")
        private val CLOCK_OUT_BUTTONS = listOf("下班打卡")

        // ── 兜底关键词：有些公司可能显示不同文字 ──
        private val CLOCK_IN_FALLBACK = listOf("上班打卡", "签到", "上班签到")
        private val CLOCK_OUT_FALLBACK = listOf("下班打卡", "签退", "下班签退")

        /** 当前阶段 */
        const val PHASE_WAIT_APP = 0     // 等待钉钉启动
        const val PHASE_CLICK_NAV = 1    // 已启动，需要点顶部"打卡"导航
        const val PHASE_CLICK_PUNCH = 2  // 已进入考勤页，需要点实际的打卡按钮
        const val PHASE_DONE = 3         // 完成
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前处于哪个阶段 */
    @Volatile
    private var currentPhase = PHASE_WAIT_APP

    private var retryCount = 0
    private val maxRetries = 20           // 最大轮询次数（每2秒一次 ≈ 40秒超时）
    private val pollInterval = 2000L      // 每2秒轮询一次
    private val initialDelay = 4000L      // 首次延迟4秒等钉钉加载
    private val phaseTransitionDelay = 3000L  // 阶段切换后等待页面加载
    private val successDelay = 2000L      // 点击成功后延迟确认

    /**
     * 主轮询 Runnable：按当前阶段执行对应逻辑
     */
    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (pendingTaskId == -1L || pendingAction == null) {
                Log.d(TAG, "poll: 无任务，停止")
                return
            }
            if (currentPhase >= PHASE_DONE) {
                Log.d(TAG, "poll: 已完成，停止")
                return
            }
            if (retryCount >= maxRetries) {
                Log.w(TAG, "poll: 重试${retryCount}次超时，currentPhase=$currentPhase")
                onClockFail("超时（第${currentPhase}阶段未完成），请检查钉钉是否在前台")
                return
            }

            retryCount++
            Log.d(TAG, "poll: #$retryCount/$maxRetries | phase=$currentPhase | action=${pendingAction?.name}")

            val root = rootInActiveWindow
            if (root == null) {
                Log.d(TAG, "poll: root为空，等待钉钉窗口")
                scheduleNext()
                return
            }

            try {
                executeCurrentPhase(root)
            } catch (e: Exception) {
                Log.e(TAG, "poll: 异常", e)
            } finally {
                root.recycle()
            }

            if (currentPhase < PHASE_DONE) {
                scheduleNext()
            }
        }

        private fun scheduleNext() {
            handler.postDelayed(pollRunnable, pollInterval)
        }
    }

    /**
     * 根据当前阶段执行对应的操作
     */
    private fun executeCurrentPhase(root: AccessibilityNodeInfo) {
        when (currentPhase) {
            PHASE_WAIT_APP -> {
                // 检查是否已经在钉钉界面了
                val pkg = root.packageName?.toString() ?: ""
                if (pkg == DINGTALK_PACKAGE || pkg.contains("dingtalk", true) || pkg.contains("rimet")) {
                    Log.i(TAG, "检测到钉钉已打开，进入 Phase 1")
                    transitionTo(PHASE_CLICK_NAV)
                    // 立即尝试执行 Phase 1
                    executeCurrentPhase(root)
                } else {
                    Log.d(TAG, "等待钉钉打开... 当前包名=$pkg")
                }
            }

            PHASE_CLICK_NAV -> {
                // 在首页找顶部导航栏的"打卡"按钮
                if (tryClickNavClockButton(root)) {
                    Log.i(TAG, ">>> 已点击导航栏「打卡」，进入 Phase 2")
                    transitionTo(PHASE_CLICK_PUNCH)
                    // 重置重试计数，给新阶段更多时间
                    retryCount = 0
                } else {
                    Log.d(TAG, "Phase 1: 未找到导航栏「打卡」按钮，继续查找...")
                    dumpPageInfo(root)
                }
            }

            PHASE_CLICK_PUNCH -> {
                // 在考勤页找实际的打卡按钮（"上班打卡"/"下班打卡"）
                if (tryClickPunchButton(root)) {
                    Log.i(TAG, ">>> 已点击打卡按钮！完成！")
                    transitionTo(PHASE_DONE)
                    val label = when (pendingAction) {
                        ClockAction.CLOCK_IN -> "上班打卡"
                        ClockAction.CLOCK_OUT -> "下班打卡"
                        null -> "打卡"
                    }
                    handler.postDelayed({ onClockSuccess(label) }, successDelay)
                } else {
                    Log.d(TAG, "Phase 2: 未找到打卡按钮，继续查找...")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Phase 1：点击首页导航栏的「打卡」入口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在钉钉首页顶部导航栏中找到"打卡"按钮并点击
     *
     * 导航栏结构（从截图）：日历 | 待办 | DING | ☐ 打卡 | 通话
     *
     * 策略：
     * 1. 用 findAccessibilityNodeInfosByText("打卡") 找所有包含该文字的节点
     * 2. 排除底部 tab 的"打卡"（考勤页面底部的 tab 文字也是"打卡"）
     * 3. 排除已经包含"上班"/"下班"的节点（那是真正的打卡按钮不是导航入口）
     * 4. 找到可点击的父节点并执行点击
     */
    private fun tryClickNavClockButton(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText("打卡")
        if (nodes.isEmpty()) {
            Log.d(TAG, "tryClickNavClockButton: 未找到任何含'打卡'的节点")
            return false
        }

        Log.d(TAG, "tryClickNavClockButton: 找到 ${nodes.size} 个含'打卡'的节点，逐一检查...")

        for (node in nodes) {
            try {
                val nodeText = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                Log.d(TAG, "  节点: text='$nodeText' desc='$desc' id='$viewId' clickable=${node.isClickable}")

                // 排除规则：
                // 1. 排除真正的打卡按钮（"上班打卡"/"下班打卡" 包含"打卡"但不是导航入口）
                if (CLOCK_IN_BUTTONS.any { nodeText == it } ||
                    CLOCK_OUT_BUTTONS.any { nodeText == it }) {
                    Log.d(TAG, "  → 排除：这是打卡按钮本身，不是导航入口")
                    continue
                }
                if (CLOCK_IN_FALLBACK.any { nodeText.contains(it) } ||
                    CLOCK_OUT_FALLBACK.any { nodeText.contains(it) }) {
                    continue
                }

                // 2. 排除底部 tab 区域的节点（通常在屏幕底部 y > 90%）
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val screenHeight = resources.displayMetrics.heightPixels
                if (bounds.bottom > screenHeight * 0.85f) {
                    Log.d(TAG, "  → 排除：位于屏幕底部(${bounds.bottom}/${screenHeight})，可能是底部tab")
                    continue
                }

                // 3. 找到可点击的父节点并点击
                val target = findClickableParent(node, 6) ?: node
                if (target.isClickable || node.isClickable) {
                    val clickTarget = if (node.isClickable) node else target
                    clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "  ✓ 成功点击导航栏「打卡」! text='$nodeText'")
                    return true
                } else {
                    Log.d(TAG, "  → 不可点击，尝试 ACTION_CLICK 直接点")
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "  ✓ 强制点击导航栏「打卡」! text='$nodeText'")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "  节点处理异常", e)
            } finally {
                node.recycle()
            }
        }

        return false
    }

    // ═══════════════════════════════════════════════════════════════
    //  Phase 2：点击考勤页面中间的大蓝色「上班打卡/下班打卡」按钮
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在考勤页面中找到实际的打卡按钮并点击
     *
     * 从截图看，这是一个大的蓝色圆形区域，文字为 "上班打卡" 或 "下班打卡"
     * 位于页面中央偏下的位置
     */
    private fun tryClickPunchButton(root: AccessibilityNodeInfo): Boolean {
        val action = pendingAction ?: return false

        // 根据动作类型确定要搜索的关键词列表（优先精确匹配，再兜底）
        val primaryKeywords = when (action) {
            ClockAction.CLOCK_IN -> CLOCK_IN_BUTTONS
            ClockAction.CLOCK_OUT -> CLOCK_OUT_BUTTONS
        }
        val fallbackKeywords = when (action) {
            ClockAction.CLOCK_IN -> CLOCK_IN_FALLBACK
            ClockAction.CLOCK_OUT -> CLOCK_OUT_FALLBACK
        }
        val actionLabel = when (action) {
            ClockAction.CLOCK_IN -> "上班打卡"
            ClockAction.CLOCK_OUT -> "下班打卡"
        }

        // 方式1：精确文本匹配
        if (tryClickByText(root, primaryKeywords, actionLabel)) return true

        // 方式2：兜底关键词
        if (tryClickByText(root, fallbackKeywords, actionLabel)) return true

        // 方式3：遍历整个节点树，用 contentDescription 匹配
        if (tryClickByTreeTraversal(root, primaryKeywords + fallbackKeywords, actionLabel)) return true

        Log.d(TAG, "tryClickPunchButton: 未找到打卡按钮, action=$action")
        return false
    }

    /**
     * 通过文本搜索找到打卡按钮并点击
     */
    private fun tryClickByText(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        actionLabel: String
    ): Boolean {
        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                try {
                    val nodeText = node.text?.toString()?.trim() ?: ""

                    // 排除导航栏的小"打卡"（只保留完整的"上班打卡"/"下班打卡"）
                    if (nodeText == "打卡") {
                        Log.d(TAG, "  [排除] 这是导航入口'打卡'，不是打卡按钮")
                        continue
                    }

                    val target = findClickableParent(node, 10) ?: node
                    if (target.isClickable || node.isClickable) {
                        val clickTarget = if (node.isClickable) node else target
                        clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "  ✓ 成功点击 [$actionLabel]! (text='$nodeText', keyword='$keyword')")
                        return true
                    } else {
                        // 即使不可点击也尝试直接操作
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "  ✓ 强制点击 [$actionLabel]! (text='$nodeText')")
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  节点异常", e)
                } finally {
                    node.recycle()
                }
            }
        }
        return false
    }

    /**
     * 遍历整棵节点树，通过 contentDescription 或 partial text 匹配
     */
    private fun tryClickByTreeTraversal(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        actionLabel: String
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val maxVisit = 500 // 防止无限遍历

        while (queue.isNotEmpty() && visited < maxVisit) {
            val node = queue.removeFirst()
            visited++
            try {
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                val text = node.text?.toString()?.trim() ?: ""

                // 检查是否匹配
                val matchedKeyword = keywords.firstOrNull {
                    desc.equals(it, ignoreCase = true) ||
                            text.equals(it, ignoreCase = true) ||
                            desc.contains(it, ignoreCase = true) && !desc.contains("打卡") && it != "打卡"
                }

                if (matchedKeyword != null && text != "打卡") { // 排除纯"打卡"导航文字
                    val target = findClickableParent(node, 10) ?: node
                    if (target.isClickable) {
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "  ✓ 遍历命中: [$actionLabel] desc='$desc' text='$text'")
                        return true
                    }
                }

                // 添加子节点到队列
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } catch (e: Exception) {
                // 忽略单个节点的异常
            }
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 向上查找可点击的父节点
     */
    private fun findClickableParent(node: AccessibilityNodeInfo, maxDepth: Int): AccessibilityNodeInfo? {
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
     * 切换到新阶段（重置 retry 计数）
     */
    private fun transitionTo(newPhase: Int) {
        Log.i(TAG, "阶段切换: $currentPhase → $newPhase")
        currentPhase = newPhase
    }

    /**
     * 输出页面调试信息（用于排查找不到节点的问题）
     */
    private fun dumpPageInfo(root: AccessibilityNodeInfo) {
        if (retryCount % 5 != 0) return // 每10秒输出一次，避免刷屏
        val sb = StringBuilder()
        sb.appendLine("=== 页面快照 (retry=$retryCount) ===")
        sb.appendLine("package=${root.packageName}")
        collectNodeTexts(root, sb, 0, 50) // 最多收集50个节点
        Log.d(TAG, sb.toString())
    }

    private fun collectNodeTexts(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int, remaining: Int) {
        if (remaining <= 0) return
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        if (text.isNotEmpty() || desc.isNotEmpty()) {
            val indent = "  ".repeat(depth.coerceAtMost(4))
            sb.appendLine("${indent}'$text' (desc='$desc' click=${node.isClickable})")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodeTexts(it, sb, depth + 1, remaining - 1) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  外部调用接口 & 生命周期
    // ═══════════════════════════════════════════════════════════════

    /**
     * 被 ClockAlarmReceiver / MainActivity 调用
     * 开始一个新的打卡流程
     */
    fun onTaskStart() {
        Log.i(TAG, "═══ onTaskStart ═══ action=${pendingAction?.name} task=$pendingTaskId")
        currentPhase = PHASE_WAIT_APP
        hasClickedSuccess = false
        retryCount = 0
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, initialDelay)
    }

    private var hasClickedSuccess = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "onServiceConnected: 无障碍服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacks(pollRunnable)
        Log.i(TAG, "onUnbind: 服务断开")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (pendingTaskId == -1L || pendingAction == null) return
        if (hasClickedSuccess) return

        val packageName = event.packageName?.toString() ?: return

        // 只关心钉钉的事件
        if (packageName != DINGTALK_PACKAGE &&
            !packageName.contains("dingtalk", true) &&
            !packageName.contains("rimet", true)
        ) return

        // 收到钉钉窗口变化事件时，提前触发一次轮询
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.d(TAG, "onAccess: 收到钉钉事件 type=${event.eventType}, 提前轮询")
                handler.removeCallbacks(pollRunnable)
                handler.post(pollRunnable)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  结果回调
    // ═══════════════════════════════════════════════════════════════

    private fun onClockSuccess(actionLabel: String) {
        if (hasClickedSuccess) return
        hasClickedSuccess = true
        Log.i(TAG, "═══ 打卡成功! [$actionLabel] ═══")
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

    private fun onClockFail(reason: String) {
        val actionLabel = when (pendingAction) {
            ClockAction.CLOCK_IN -> "上班打卡"
            ClockAction.CLOCK_OUT -> "下班打卡"
            null -> "打卡"
        }
        Log.w(TAG, "═══ 打卡失败! [$actionLabel] $reason ═══")
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

    fun resetState() {
        pendingTaskId = -1L
        pendingAction = null
        currentPhase = PHASE_WAIT_APP
        hasClickedSuccess = false
        retryCount = 0
        handler.removeCallbacks(pollRunnable)
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt: 服务被中断")
        resetState()
    }
}
