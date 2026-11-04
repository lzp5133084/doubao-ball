package com.junge.doubaoball

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 对外接口接收器（预留接口）：
 * 其他程序发以下广播即可控制本外壳，无需打开界面——
 *   com.junge.doubaoball.ACTION_CALL_START   发起豆包语音通话
 *   com.junge.doubaoball.ACTION_CALL_END     挂断通话
 *   com.junge.doubaoball.ACTION_CALL_TOGGLE  暂停/继续切换
 *   com.junge.doubaoball.ACTION_BALL_SHOW    显示悬浮球
 *   com.junge.doubaoball.ACTION_BALL_HIDE    隐藏悬浮球
 */
class InterfaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a11y = DoubaoA11yService.instance
        when (intent.action) {
            "com.junge.doubaoball.ACTION_CALL_START" -> {
                if (StateCenter.state == StateCenter.State.IDLE && a11y != null) {
                    StateCenter.set(StateCenter.State.LAUNCHING)
                    if (!a11y.launchDoubao(context)) StateCenter.reset()
                }
            }
            "com.junge.doubaoball.ACTION_CALL_END" -> {
                if (StateCenter.state == StateCenter.State.IN_CALL ||
                    StateCenter.state == StateCenter.State.PAUSED
                ) {
                    a11y?.endCall()
                    StateCenter.reset()
                }
            }
            "com.junge.doubaoball.ACTION_CALL_TOGGLE" -> {
                when (StateCenter.state) {
                    StateCenter.State.IN_CALL ->
                        if (a11y?.pauseCall() == true) StateCenter.set(StateCenter.State.PAUSED)
                    StateCenter.State.PAUSED ->
                        if (a11y?.resumeCall() == true) StateCenter.set(StateCenter.State.IN_CALL)
                    else -> {}
                }
            }
            "com.junge.doubaoball.ACTION_BALL_SHOW" -> FloatingBallService.start(context)
            "com.junge.doubaoball.ACTION_BALL_HIDE" -> FloatingBallService.stop(context)
        }
    }
}
