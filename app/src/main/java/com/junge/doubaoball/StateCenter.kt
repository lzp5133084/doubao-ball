package com.junge.doubaoball

/**
 * 全局状态中心：悬浮球外壳的核心状态机
 *
 * 状态流转：
 *  IDLE(空闲) --单击--> LAUNCHING(正在打开豆包) --自动点击语音通话成功--> IN_CALL(通话中)
 *  IN_CALL --单击--> PAUSED(已暂停)   PAUSED --单击--> IN_CALL
 *  IN_CALL/PAUSED --双击--> IDLE(挂断关闭)
 */
object StateCenter {

    enum class State {
        IDLE, LAUNCHING, IN_CALL, PAUSED
    }

    @Volatile
    var state: State = State.IDLE
        private set

    interface Listener { fun onStateChanged(s: State) }

    private val listeners = mutableListOf<Listener>()

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun set(s: State) {
        if (state == s) return
        state = s
        for (l in listeners.toList()) l.onStateChanged(s)
    }

    fun reset() = set(State.IDLE)
}
