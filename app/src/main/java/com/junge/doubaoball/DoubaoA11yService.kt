package com.junge.doubaoball

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 无障碍服务：外壳的"大脑"
 * 职责：回桌面长按豆包图标点「打电话」直达语音通话；通话中点"暂停/挂断"按钮
 */
class DoubaoA11yService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: DoubaoA11yService? = null
            private set

        fun isRunning() = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var store: RecordStore

    private val pauseKeywords = listOf("暂停", "静音", "打断", "闭麦")
    private val resumeKeywords = listOf("继续", "取消静音", "开麦", "恢复")
    private val endKeywords = listOf("挂断", "结束通话", "结束", "退出通话")

    /** 连续重试计数 */
    private var launchRetry = 0

    /** 快捷方式启动流程的阶段：0=等桌面出现并找豆包图标 1=等长按菜单出现并点"打电话" */
    @Volatile
    private var shortcutStage = -1

    /** 悬浮球单击唤醒入口：走「回桌面→长按豆包图标→点打电话」快捷方式路径 */
    fun startCallViaShortcut() {
        launchRetry = 0
        shortcutStage = 0
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.removeCallbacks(shortcutTimeoutRunnable)
        handler.postDelayed(shortcutTimeoutRunnable, 9000) // 全流程9秒超时保护
    }

    private val shortcutTimeoutRunnable = Runnable {
        if (StateCenter.state == StateCenter.State.LAUNCHING) {
            shortcutStage = -1
            StateCenter.reset()
            store.add("单击唤醒·快捷方式打电话", "失败：超时")
            toast("操作超时，请重试")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        store = RecordStore(this)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (StateCenter.state != StateCenter.State.LAUNCHING) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        // 排除本App自己的窗口干扰
        if (event.packageName == packageName) return

        when (shortcutStage) {
            0 -> {
                // 等桌面出现 → 找"豆包"图标并长按
                handler.removeCallbacks(findIconRunnable)
                handler.postDelayed(findIconRunnable, 450)
            }
            1 -> {
                // 长按后等快捷菜单出现 → 点"打电话"
                handler.removeCallbacks(clickMenuRunnable)
                handler.postDelayed(clickMenuRunnable, 350)
            }
        }
    }

    /** 阶段0：在桌面上找"豆包"图标 → 模拟手指按住图标900ms弹出快捷菜单 */
    private val findIconRunnable = Runnable {
        if (shortcutStage != 0 || StateCenter.state != StateCenter.State.LAUNCHING) return@Runnable
        val icon = findInAllWindows("豆包")
        if (icon == null) {
            retryFindIcon()
        } else {
            val rect = Rect()
            icon.getBoundsInScreen(rect)
            if (rect.width() <= 0) {
                retryFindIcon()
            } else {
                // 真实手指长按（所有桌面都认），比系统的LONG_CLICK可靠
                val ok = longPressAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                if (ok) {
                    shortcutStage = 1
                    launchRetry = 0
                    // 菜单弹出需要动画时间，主动调度一次点击尝试
                    handler.removeCallbacks(clickMenuRunnable)
                    handler.postDelayed(clickMenuRunnable, 800)
                } else {
                    retryFindIcon()
                }
            }
        }
    }

    private fun retryFindIcon() {
        launchRetry++
        if (launchRetry <= 4) {
            handler.postDelayed(findIconRunnable, 800)
        } else {
            finishLaunchFail("没找到桌面上的豆包图标", "请确认豆包图标就在桌面当前这一屏（别放文件夹里）")
        }
    }

    /** 阶段1：在弹出的快捷菜单里点"打电话"（菜单是独立窗口，必须遍历所有窗口找） */
    private val clickMenuRunnable = Runnable {
        if (shortcutStage != 1 || StateCenter.state != StateCenter.State.LAUNCHING) return@Runnable
        val menu = findInAllWindows("打电话")
        if (menu == null) {
            retryClickMenu()
        } else {
            val ok = clickNode(menu)
            if (ok) {
                handler.removeCallbacks(shortcutTimeoutRunnable)
                shortcutStage = -1
                launchRetry = 0
                StateCenter.set(StateCenter.State.IN_CALL)
                store.add("单击唤醒·快捷方式打电话", "成功")
                prefs.totalCalls = prefs.totalCalls + 1
            } else {
                retryClickMenu()
            }
        }
    }

    private fun retryClickMenu() {
        launchRetry++
        if (launchRetry <= 5) {
            handler.postDelayed(clickMenuRunnable, 500)
        } else {
            finishLaunchFail("菜单里没找到「打电话」", "长按后没认出打电话选项，请截图发我看看菜单")
        }
    }

    /**
     * 在所有可见窗口里找文本/描述匹配的节点。
     * 关键：快捷菜单、弹窗是独立窗口，只在主窗口里找会漏掉。
     */
    private fun findInAllWindows(keyword: String): AccessibilityNodeInfo? {
        val wins = windows ?: return null
        for (w in wins) {
            val root = w.root ?: continue
            val found = findNode(root, keyword, keyword)
            if (found != null) return found
        }
        return null
    }

    /** 模拟手指在指定位置按住900毫秒（长按手势），用于弹出桌面图标的快捷菜单 */
    private fun longPressAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 900))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun finishLaunchFail(result: String, tip: String) {
        handler.removeCallbacks(shortcutTimeoutRunnable)
        shortcutStage = -1
        launchRetry = 0
        StateCenter.reset()
        store.add("单击唤醒·快捷方式打电话", "失败：$result")
        toast(tip)
    }

    /** 外部调用：暂停通话 */
    fun pauseCall(): Boolean {
        val ok = clickByKeywords(pauseKeywords)
        store.add("暂停通话", if (ok) "成功" else "失败：未找到暂停/静音按钮")
        return ok
    }

    /** 外部调用：继续通话（静音键一般是开关式，再点一次即可恢复） */
    fun resumeCall(): Boolean {
        var ok = clickByKeywords(resumeKeywords)
        if (!ok) ok = clickByKeywords(pauseKeywords) // 同一个开关按钮
        store.add("继续通话", if (ok) "成功" else "失败：未找到继续按钮")
        return ok
    }

    /** 外部调用：挂断并退出 */
    fun endCall(): Boolean {
        var ok = clickByKeywords(endKeywords)
        if (!ok) {
            // 兜底：按返回键退出通话界面
            ok = performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (prefs.backHomeAfterHangup) {
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 400)
        }
        store.add("双击挂断关闭", if (ok) "成功" else "失败")
        return ok
    }

    /** 按内置关键词匹配并点击按钮 */
    private fun clickByKeywords(keywords: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            for (kw in keywords) {
                val node = findNode(root, kw, kw)
                if (node != null) return clickNode(node)
            }
            return false
        } finally {
            root.recycle()
        }
    }

    /** 递归查找文本或描述包含keyword的可点击节点 */
    private fun findNode(node: AccessibilityNodeInfo, textKw: String, descKw: String): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if ((textKw.isNotEmpty() && text.contains(textKw)) ||
            (descKw.isNotEmpty() && desc.contains(descKw))
        ) {
            // 节点本身或向上找可点击的祖先
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target != null) return target
            return node // 实在没有可点击祖先，用坐标点它
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNode(child, textKw, descKw)
            if (found != null) return found
        }
        return null
    }

    /** 点击节点：优先系统点击，不行就用坐标手势点 */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    /** 在屏幕指定坐标模拟一次点按 */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
