package com.junge.doubaoball

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 无障碍服务：外壳的"大脑"
 * 职责：监视窗口变化 → 识别豆包界面 → 自动点击"语音通话/暂停/挂断"按钮
 */
class DoubaoA11yService : AccessibilityService() {

    companion object {
        const val DOUBAO_PKG = "com.larus.nova"

        @Volatile
        var instance: DoubaoA11yService? = null
            private set

        fun isRunning() = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var store: RecordStore

    /** 语音通话按钮的内置关键词（豆包不同版本界面文案可能不同，全部列上逐一尝试） */
    private val callKeywords = listOf("语音通话", "打电话", "实时通话", "语音对话", "语音", "通话")
    private val pauseKeywords = listOf("暂停", "静音", "打断", "闭麦")
    private val resumeKeywords = listOf("继续", "取消静音", "开麦", "恢复")
    private val endKeywords = listOf("挂断", "结束通话", "结束", "退出通话")

    /** 连续重试计数 */
    private var launchRetry = 0

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
        // 只在"正在唤醒豆包"状态下，监听到豆包窗口出现时执行自动点击
        if (StateCenter.state == StateCenter.State.LAUNCHING &&
            event.packageName == DOUBAO_PKG &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            // 界面刚加载，节点可能还没铺完，延迟一点再点
            handler.removeCallbacks(autoClickCallRunnable)
            handler.postDelayed(autoClickCallRunnable, 700)
        }
    }

    private val autoClickCallRunnable = Runnable { tryClickCallButton() }

    /** 尝试点击豆包里的"语音通话"按钮，失败自动重试 */
    private fun tryClickCallButton() {
        if (StateCenter.state != StateCenter.State.LAUNCHING) return
        launchRetry++
        if (clickByFeature(prefs.customCallNode, callKeywords)) {
            launchRetry = 0
            StateCenter.set(StateCenter.State.IN_CALL)
            store.add("单击唤醒·自动进入语音通话", "成功")
            Prefs(this).totalCalls = Prefs(this).totalCalls + 1
        } else if (launchRetry <= 6) {
            handler.postDelayed(autoClickCallRunnable, 900)
        } else {
            launchRetry = 0
            StateCenter.reset()
            store.add("单击唤醒·自动进入语音通话", "失败：没找到语音按钮")
            toast("没找到豆包的语音按钮，请在App里用「校准工具」告诉我它在哪")
        }
    }

    /** 外部调用：暂停通话 */
    fun pauseCall(): Boolean {
        val ok = clickByFeature(prefs.customPauseNode, pauseKeywords)
        store.add("暂停通话", if (ok) "成功" else "失败：未找到暂停/静音按钮")
        return ok
    }

    /** 外部调用：继续通话（静音键一般是开关式，再点一次即可恢复） */
    fun resumeCall(): Boolean {
        var ok = clickByFeature(prefs.customPauseNode, resumeKeywords)
        if (!ok) ok = clickByFeature(prefs.customPauseNode, pauseKeywords) // 同一个开关按钮
        store.add("继续通话", if (ok) "成功" else "失败：未找到继续按钮")
        return ok
    }

    /** 外部调用：挂断并退出 */
    fun endCall(): Boolean {
        var ok = clickByFeature(prefs.customEndNode, endKeywords)
        if (!ok) {
            // 兜底：按返回键退出通话界面
            ok = performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (Prefs(this).backHomeAfterHangup) {
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 400)
        }
        store.add("双击挂断关闭", if (ok) "成功" else "失败")
        return ok
    }

    /**
     * 按特征点击按钮。
     * 优先使用用户校准保存的特征（text|desc|x,y），没有则用内置关键词匹配。
     */
    private fun clickByFeature(custom: String?, keywords: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            // 校准特征优先
            if (!custom.isNullOrBlank()) {
                val parts = custom.split("|")
                if (parts.size == 2) {
                    val node = findNode(root, parts[0], parts[1])
                    if (node != null) return clickNode(node)
                }
            }
            // 关键词匹配（按优先级顺序）
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

    /** 校准工具用：抓取当前窗口所有可点击节点的特征清单 */
    fun dumpClickableNodes(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<String>()
        dumpNode(root, result, 0)
        root.recycle()
        return result
    }

    private fun dumpNode(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 12 || out.size >= 80) return
        if (node.isClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = (node.text?.toString() ?: "").take(20)
            val desc = (node.contentDescription?.toString() ?: "").take(20)
            if (text.isNotBlank() || desc.isNotBlank()) {
                out.add("「$text」描:$desc 位:(${rect.centerX()},${rect.centerY()})")
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, out, depth + 1) }
        }
    }

    /** 打开豆包App */
    fun launchDoubao(ctx: Context): Boolean {
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(DOUBAO_PKG)
            if (intent == null) {
                toast("未检测到豆包App，请先安装豆包")
                false
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                ctx.startActivity(intent)
                true
            }
        } catch (e: Exception) {
            toast("打开豆包失败：${e.message}")
            false
        }
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
