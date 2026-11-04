package com.junge.doubaoball

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置存储：悬浮球位置/大小、手势偏好、通话统计
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("doubao_ball_prefs", Context.MODE_PRIVATE)

    var ballX: Int
        get() = sp.getInt("ball_x", -1)
        set(v) = sp.edit().putInt("ball_x", v).apply()

    var ballY: Int
        get() = sp.getInt("ball_y", -1)
        set(v) = sp.edit().putInt("ball_y", v).apply()

    /** 球直径，单位dp */
    var ballSizeDp: Int
        get() = sp.getInt("ball_size", 62)
        set(v) = sp.edit().putInt("ball_size", v).apply()

    /** 待机时双击是否隐藏悬浮球 */
    var doubleTapHideWhenIdle: Boolean
        get() = sp.getBoolean("double_tap_hide", false)
        set(v) = sp.edit().putBoolean("double_tap_hide", v).apply()

    /** 通话结束后是否自动回到桌面 */
    var backHomeAfterHangup: Boolean
        get() = sp.getBoolean("back_home", true)
        set(v) = sp.edit().putBoolean("back_home", v).apply()

    /** 记录总数统计 */
    var totalCalls: Int
        get() = sp.getInt("total_calls", 0)
        set(v) = sp.edit().putInt("total_calls", v).apply()
}
