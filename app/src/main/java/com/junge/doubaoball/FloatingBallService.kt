package com.junge.doubaoball

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮球服务：在任意界面上显示一颗黑金科技风的悬浮球
 * 单击=唤醒豆包/暂停继续，双击=挂断关闭，可自由拖动、自动贴边
 */
class FloatingBallService : Service(), StateCenter.Listener {

    companion object {
        const val ACTION_SHOW = "com.junge.doubaoball.BALL_SHOW"
        const val ACTION_HIDE = "com.junge.doubaoball.BALL_HIDE"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, FloatingBallService::class.java).setAction(ACTION_SHOW))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FloatingBallService::class.java).setAction(ACTION_HIDE))
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var ballView: BallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        StateCenter.addListener(this)
        startForeground(1001, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeBall()
                stopSelf()
            }
            else -> showBall()
        }
        return START_STICKY
    }

    override fun onStateChanged(s: StateCenter.State) {
        handler.post { ballView?.setState(s) }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ball", "悬浮球运行状态", NotificationManager.IMPORTANCE_MIN)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "ball")
            .setContentTitle("豆包悬浮球运行中")
            .setContentText("单击球:对话/暂停 · 双击球:挂断")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBall() {
        if (ballView != null) return
        val sizePx = dp(prefs.ballSizeDp)
        val view = BallView(this)
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            sizePx, sizePx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        params.x = if (prefs.ballX >= 0) prefs.ballX else screenW - sizePx - dp(12)
        params.y = if (prefs.ballY >= 0) prefs.ballY else screenH / 3

        attachGestures(view, params)
        wm.addView(view, params)
        ballView = view
        ballParams = params
        view.setState(StateCenter.state)
    }

    private fun removeBall() {
        ballView?.let { runCatching { wm.removeView(it) } }
        ballView = null
        ballParams = null
    }

    /** 手势：区分拖动 / 单击 / 双击 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        var lastTapTime = 0L
        var singleTapRunnable: Runnable? = null

        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        wm.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        stickToEdge(params)
                        prefs.ballX = params.x; prefs.ballY = params.y
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 320) {
                            // 双击：取消待执行的单击
                            singleTapRunnable?.let { handler.removeCallbacks(it) }
                            singleTapRunnable = null
                            lastTapTime = 0
                            onDoubleTap()
                        } else {
                            lastTapTime = now
                            val r = Runnable { onSingleTap() }
                            singleTapRunnable = r
                            handler.postDelayed(r, 330)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 单击：待机→唤醒豆包并进入通话；通话中→暂停；暂停中→继续 */
    private fun onSingleTap() {
        val a11y = DoubaoA11yService.instance
        when (StateCenter.state) {
            StateCenter.State.IDLE -> {
                if (a11y == null) {
                    toast("请先开启「无障碍权限」，我才能帮你点豆包")
                    return
                }
                StateCenter.set(StateCenter.State.LAUNCHING)
                a11y.startCallViaShortcut()
            }
            StateCenter.State.LAUNCHING -> toast("正在唤醒豆包，请稍候…")
            StateCenter.State.IN_CALL -> {
                if (a11y?.pauseCall() == true) StateCenter.set(StateCenter.State.PAUSED)
            }
            StateCenter.State.PAUSED -> {
                if (a11y?.resumeCall() == true) StateCenter.set(StateCenter.State.IN_CALL)
            }
        }
    }

    /** 双击：通话中/暂停中→挂断；待机→可选隐藏球 */
    private fun onDoubleTap() {
        val a11y = DoubaoA11yService.instance
        when (StateCenter.state) {
            StateCenter.State.IN_CALL, StateCenter.State.PAUSED -> {
                a11y?.endCall()
                StateCenter.reset()
            }
            StateCenter.State.LAUNCHING -> {
                StateCenter.reset()
                toast("已取消唤醒")
            }
            StateCenter.State.IDLE -> {
                if (prefs.doubleTapHideWhenIdle) {
                    removeBall()
                    toast("悬浮球已隐藏，打开App可重新显示")
                }
            }
        }
    }

    /** 松手后吸附到最近的屏幕边缘 */
    private fun stickToEdge(params: WindowManager.LayoutParams) {
        val screenW = resources.displayMetrics.widthPixels
        val targetX = if (params.x + params.width / 2 < screenW / 2) dp(6)
        else screenW - params.width - dp(6)
        val animator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180
            addUpdateListener {
                params.x = it.animatedValue as Int
                ballView?.let { v -> runCatching { wm.updateViewLayout(v, params) } }
            }
        }
        animator.start()
    }

    private fun toast(msg: String) =
        handler.post { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        StateCenter.removeListener(this)
        removeBall()
        super.onDestroy()
    }
}

/**
 * 黑金2027科技风悬浮球（纯代码绘制，无图片资源）：
 *  - 待机：黑金球体 + 金色呼吸光晕
 *  - 唤醒中：旋转的金色扫描弧
 *  - 通话中：跳动的金色声波
 *  - 暂停：暗金半亮 + 双竖线图标
 */
class BallView(context: Context) : View(context) {

    private var state = StateCenter.State.IDLE
    private var animProgress = 0f   // 0..1 循环
    private var animator: ValueAnimator? = null

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    companion object {
        val GOLD = Color.parseColor("#FFD76A")
        val DEEP_GOLD = Color.parseColor("#D4AF37")
        val DARK_GOLD = Color.parseColor("#8A6D2F")
    }

    fun setState(s: StateCenter.State) {
        state = s
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        val r = min(w, h) / 2

        // ── 外圈光晕（待机呼吸 / 通话中强金光 / 暂停暗光）──
        val breathe = (0.55f + 0.45f * kotlin.math.sin(animProgress * Math.PI * 2).toFloat())
        val haloAlpha = when (state) {
            StateCenter.State.IDLE -> (70 * breathe).toInt()
            StateCenter.State.LAUNCHING -> 110
            StateCenter.State.IN_CALL -> (120 + 60 * breathe).toInt()
            StateCenter.State.PAUSED -> 45
        }
        haloPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.argb(haloAlpha, 255, 215, 106), Color.TRANSPARENT),
            floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, haloPaint)

        // ── 球体：黑金径向渐变 ──
        val bodyR = r * 0.72f
        bodyPaint.shader = RadialGradient(
            cx - bodyR * 0.3f, cy - bodyR * 0.35f, bodyR * 1.4f,
            intArrayOf(Color.parseColor("#3A3A3A"), Color.parseColor("#101010"), Color.BLACK),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, bodyR, bodyPaint)

        // ── 金边描环 ──
        ringPaint.shader = LinearGradient(
            cx - bodyR, cy - bodyR, cx + bodyR, cy + bodyR,
            GOLD, DARK_GOLD, Shader.TileMode.CLAMP
        )
        ringPaint.strokeWidth = r * 0.045f
        canvas.drawCircle(cx, cy, bodyR, ringPaint)

        when (state) {
            StateCenter.State.LAUNCHING -> {
                // 旋转扫描弧
                arcPaint.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, GOLD), null)
                arcPaint.strokeWidth = r * 0.07f
                val sweepR = bodyR * 0.78f
                canvas.save()
                canvas.rotate(animProgress * 360f, cx, cy)
                canvas.drawArc(RectF(cx - sweepR, cy - sweepR, cx + sweepR, cy + sweepR), 0f, 90f, false, arcPaint)
                canvas.restore()
                drawCenterText(canvas, cx, cy, bodyR, "…")
            }
            StateCenter.State.IN_CALL -> {
                // 三道金色声波
                wavePaint.color = GOLD
                wavePaint.strokeWidth = r * 0.055f
                val gap = bodyR * 0.22f
                for (i in -1..1) {
                    val phase = ((animProgress * 2f + i * 0.33f) % 1f)
                    val barH = bodyR * (0.22f + 0.30f * kotlin.math.sin(phase * Math.PI).toFloat().let { if (it < 0) -it else it })
                    val x = cx + i * gap
                    canvas.drawLine(x, cy - barH, x, cy + barH, wavePaint)
                }
            }
            StateCenter.State.PAUSED -> {
                // 双竖线暂停图标
                wavePaint.color = DEEP_GOLD
                wavePaint.strokeWidth = r * 0.08f
                val barH = bodyR * 0.30f
                val gap = bodyR * 0.16f
                canvas.drawLine(cx - gap, cy - barH, cx - gap, cy + barH, wavePaint)
                canvas.drawLine(cx + gap, cy - barH, cx + gap, cy + barH, wavePaint)
            }
            StateCenter.State.IDLE -> drawCenterText(canvas, cx, cy, bodyR, "豆")
        }
    }

    private fun drawCenterText(canvas: Canvas, cx: Float, cy: Float, bodyR: Float, t: String) {
        textPaint.color = GOLD
        textPaint.textSize = bodyR * 0.62f
        val y = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(t, cx, y, textPaint)
    }
}
