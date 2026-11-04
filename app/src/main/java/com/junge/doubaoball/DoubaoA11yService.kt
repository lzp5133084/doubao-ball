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

    /** 阶段0：在桌面上找"豆包"图标 → 长按 */
    private val findIconRunnable = Runnable {
        if (shortcutStage != 0 || StateCenter.state != StateCenter.State.LAUNCHING) return@Runnable
        val root = rootInActiveWindow ?: return@Runnable retryFindIcon()
        val icon = findNode(root, "豆包", "豆包")
        if (icon == null) {
            root.recycle()
            retryFindIcon()
        } else {
            // 长按图标弹出快捷方式菜单
            var target: AccessibilityNodeInfo? = icon
            while (target != null && !target.isLongClickable) target = target.parent
            val ok = (target ?: icon).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            root.recycle()
            if (ok) {
                shortcutStage = 1
                launchRetry = 0
            } else {
                retryFindIcon()
            }
        }
    }

    private fun retryFindIcon() {
        launchRetry++
        if (launchRetry <= 4) {
            handler.postDelayed(findIconRunnable, 800)
        } else {
            finishLaunchFail("没找到桌面上的豆包图标", "请把豆包图标放到手机桌面当前屏，再单击悬浮球")
        }
    }

    /** 阶段1：在快捷菜单里点"打电话" */
    private val clickMenuRunnable = Runnable {
        if (shortcutStage != 1 || StateCenter.state != StateCenter.State.LAUNCHING) return@Runnable
        val root = rootInActiveWindow ?: return@Runnable retryClickMenu()
        val menu = findNode(root, "打电话", "打电话")
        if (menu == null) {
            root.recycle()
            retryClickMenu()
        } else {
            val ok = clickNode(menu)
            root.recycle()
            if (ok) {
                handler.removeCallbacks(shortcutTimeoutRunnable)
                shortcutStage = -1
                launchRetry = 0
                StateCenter.set(StateCenter.State.IN_CALL)
                store.add("单击唤醒·快捷方式打电话", "成功")
                Prefs(this).totalCalls = Prefs(this).totalCalls + 1
            } else {
                retryClickMenu()
            }
        }
    }

    private fun retryClickMenu() {
        launchRetry++
        if (launchRetry <= 3) {
            handler.postDelayed(clickMenuRunnable, 600)
        } else {
            finishLaunchFail("没找到「打电话」选项", "长按菜单里没看到打电话，请截图告诉我菜单内容")
        }
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

    /**
     * 一键自动校准：在当前窗口自动认出"语音通话"按钮并记住特征
     * 返回认出的按钮描述；认不出返回null
     */
    fun autoCalibrateCallNode(): String? {
        val root = rootInActiveWindow ?: return null
        try {
            for (kw in callKeywords) {
                val node = findNode(root, kw, kw)
                if (node != null) {
                    val text = (node.text?.toString() ?: "").take(20)
                    val desc = (node.contentDescription?.toString() ?: "").take(20)
                    prefs.customCallNode = "$text|$desc"
                    val shown = listOf(text, desc).firstOrNull { it.isNotBlank() } ?: kw
                    store.add("一键自动校准·语音键", "成功：$shown")
                    return shown
                }
            }
            return null
        } finally {
            root.recycle()
        }
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

    /** 打开豆包App（自动发现包名：任何版本的豆包都能认出来） */
    fun launchDoubao(ctx: Context): Boolean {
        val pkg = findDoubaoPackage(ctx)
        if (pkg == null) {
            toast("手机里没找到豆包App，请先到应用商店安装「豆包」")
            return false
        }
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                toast("豆包无法启动，请确认它没有被停用")
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

    /**
     * 自动发现豆包包名，三级策略：
     * ① 用上次记住的 ② 扫描所有App按名字含「豆包」匹配 ③ 已知包名兜底
     */
    private fun findDoubaoPackage(ctx: Context): String? {
        // ① 缓存
        prefs.doubaoPkg?.let { if (isInstalled(ctx, it)) return it }
        // ② 按应用名扫描（manifest已声明可见性）
        val pm = ctx.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (info in pm.queryIntentActivities(launcherIntent, 0)) {
            val label = info.loadLabel(pm).toString()
            if (label.contains("豆包")) {
                val pkg = info.activityInfo.packageName
                prefs.doubaoPkg = pkg
                store.add("自动识别豆包", "成功：$label($pkg)")
                return pkg
            }
        }
        // ③ 已知包名兜底
        for (pkg in listOf(DOUBAO_PKG, "com.larus.nova.lite")) {
            if (isInstalled(ctx, pkg)) {
                prefs.doubaoPkg = pkg
                return pkg
            }
        }
        return null
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) {
        false
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
