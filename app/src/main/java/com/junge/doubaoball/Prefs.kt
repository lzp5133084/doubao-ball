package com.junge.doubaoball

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置存储：悬浮球位置/大小、自定义按钮特征、手势偏好
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

    /**
     * 自定义按钮特征（校准工具保存），格式：text|desc|centerX,centerY
     *  null 表示未校准，用内置关键词匹配
     */
    var customCallNode: String?
        get() = sp.getString("custom_call_node", null)
        set(v) = sp.edit().putString("custom_call_node", v).apply()

    var customPauseNode: String?
        get() = sp.getString("custom_pause_node", null)
        set(v) = sp.edit().putString("custom_pause_node", v).apply()

    var customEndNode: String?
        get() = sp.getString("custom_end_node", null)
        set(v) = sp.edit().putString("custom_end_node", v).apply()

    /** 记录总数统计 */
    var totalCalls: Int
        get() = sp.getInt("total_calls", 0)
        set(v) = sp.edit().putInt("total_calls", v).apply()

    /** 自动识别到的豆包包名（缓存，避免每次扫描） */
    var doubaoPkg: String?
        get() = sp.getString("doubao_pkg", null)
        set(v) = sp.edit().putString("doubao_pkg", v).apply()

    fun clearCalibration() {
        sp.edit()
            .remove("custom_call_node")
            .remove("custom_pause_node")
            .remove("custom_end_node")
            .apply()
    }
}
