package com.junge.doubaoball

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.junge.doubaoball.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), StateCenter.Listener {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var store: RecordStore

    /** 名词解析库（大白话版） */
    private val helpMap = mapOf(
        "state" to ("当前状态" to "悬浮球现在处于什么阶段：\n· 待机：球在等你点它\n· 正在唤醒：正在帮你打开豆包\n· 通话中：豆包语音通话正在进行\n· 已暂停：通话被按了暂停"),
        "permission" to ("为什么要开权限" to "悬浮窗权限＝允许小球显示在其他App上面；\n无障碍权限＝允许小球「替你用手指点屏幕」，这样它才能帮你按下豆包里的语音按钮。本程序只操作豆包，不读取你的隐私内容。"),
        "single" to ("单击唤醒" to "手指轻点一下小球。它会：①自动打开豆包App ②自动找到并按下「语音通话」按钮。你只需要张嘴说话就行。"),
        "pause" to ("暂停/继续" to "通话中轻点一下小球＝按下豆包里的静音/暂停键，豆包就听不到你了；再点一下恢复。适合旁边有人说话时临时用。"),
        "double" to ("双击挂断" to "快速连续点两下小球＝挂断语音通话并退出，小球回到待机状态。"),
        "calibrate" to ("校准工具" to "豆包升级后界面可能变化，小球如果找不到按钮，就用这个工具「手把手」告诉它按钮长什么样：打开豆包→回来点读取→在列表里认出那个按钮→设为对应的键。一次校准长期有效。"),
        "record" to ("操作记录" to "小球每次帮你做了什么都记在这里：什么时候唤醒了豆包、暂停了几次、有没有失败。点一条可以加备注，长按可删除。"),
        "api" to ("对外接口" to "这是留给其他程序的「遥控器」：别的App发一句暗号（广播），就能让小球发起通话、挂断或显示隐藏。普通用户不用管这里。")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        store = RecordStore(this)
        StateCenter.addListener(this)

        showAppInfo()
        setupPermissionButtons()
        setupBallControls()
        setupHelpDots()
        setupCalibrate()
        setupRecords()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        refreshRecords()
    }

    override fun onDestroy() {
        StateCenter.removeListener(this)
        super.onDestroy()
    }

    override fun onStateChanged(s: StateCenter.State) {
        runOnUiThread { renderState(s) }
    }

    // ─── 顶部信息：大小 / 更新时间 / 作者 ───
    private fun showAppInfo() {
        val pm = packageManager
        val pi = pm.getPackageInfo(packageName, 0)
        val sizeBytes = File(pi.applicationInfo!!.sourceDir).length()
        val sizeMb = String.format(Locale.CHINA, "%.1f", sizeBytes / 1024f / 1024f)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(pi.lastUpdateTime))
        b.tvAppInfo.text = "v${pi.versionName} · ${sizeMb}MB\n更新 $time\n军哥懂保 · xunijiayuan"
    }

    // ─── 状态渲染 ───
    private fun renderState(s: StateCenter.State) {
        b.tvStatus.text = when (s) {
            StateCenter.State.IDLE -> "待机中 · 单击小球开始对话"
            StateCenter.State.LAUNCHING -> "正在唤醒豆包…"
            StateCenter.State.IN_CALL -> "语音通话中 · 单击暂停"
            StateCenter.State.PAUSED -> "已暂停 · 单击继续"
        }
        val color = when (s) {
            StateCenter.State.IDLE -> "#4ADE80"
            StateCenter.State.LAUNCHING -> "#FBBF24"
            StateCenter.State.IN_CALL -> "#FFD76A"
            StateCenter.State.PAUSED -> "#9BA3B4"
        }
        b.viewStatusLight.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
        }
    }

    // ─── 权限 ───
    private fun setupPermissionButtons() {
        b.btnOverlayPerm.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        b.btnA11yPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "在列表里找到「豆包悬浮球助手」打开它", Toast.LENGTH_LONG).show()
        }
        // 安卓13以上需要通知权限（前台服务提示）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun refreshPermissionStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = DoubaoA11yService.isRunning() || isA11yEnabledInSettings()
        b.tvOverlayPerm.text = "悬浮窗权限　${if (overlayOk) "✅已开" else "❌未开"}"
        b.tvA11yPerm.text = "无障碍权限　${if (a11yOk) "✅已开" else "❌未开"}"
    }

    private fun isA11yEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/${DoubaoA11yService::class.java.name}")
    }

    // ─── 悬浮球控制 ───
    private fun setupBallControls() {
        b.btnToggleBall.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "先开悬浮窗权限，球才能飘在屏幕上", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            FloatingBallService.start(this)
            b.btnToggleBall.text = "悬浮球已显示（点我再次召唤到前台）"
        }

        b.seekSize.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) prefs.ballSizeDp = 40 + progress
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                // 重启球服务让新尺寸生效
                FloatingBallService.stop(this@MainActivity)
                FloatingBallService.start(this@MainActivity)
            }
        })

        b.swDoubleHide.isChecked = prefs.doubleTapHideWhenIdle
        b.swDoubleHide.setOnCheckedChangeListener { _, c -> prefs.doubleTapHideWhenIdle = c }

        b.swBackHome.isChecked = prefs.backHomeAfterHangup
        b.swBackHome.setOnCheckedChangeListener { _, c -> prefs.backHomeAfterHangup = c }

        renderState(StateCenter.state)
    }

    // ─── 名词解析 ───
    private fun setupHelpDots() {
        val bind = mapOf(
            b.tvHelpState to "state", b.tvHelpPermission to "permission",
            b.tvHelpSingle to "single", b.tvHelpPause to "pause",
            b.tvHelpDouble to "double", b.tvHelpCalibrate to "calibrate",
            b.tvHelpRecord to "record", b.tvHelpApi to "api"
        )
        bind.forEach { (view, key) ->
            view.setOnClickListener {
                val (title, body) = helpMap[key]!!
                AlertDialog.Builder(this)
                    .setTitle(title).setMessage(body)
                    .setPositiveButton("懂了", null).show()
            }
        }
    }

    // ─── 校准工具 ───
    private fun setupCalibrate() {
        b.btnDumpNodes.setOnClickListener {
            val svc = DoubaoA11yService.instance
            if (svc == null) {
                Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val nodes = svc.dumpClickableNodes()
            b.layoutNodes.removeAllViews()
            if (nodes.isEmpty()) {
                b.layoutNodes.addView(TextView(this).apply {
                    text = "没读到按钮。请确认：豆包已打开在最前面的屏幕上。"
                    setTextColor(Color.parseColor("#F87171"))
                    textSize = 13f
                })
                return@setOnClickListener
            }
            nodes.forEach { desc -> addNodeRow(desc, svc) }
            Toast.makeText(this, "找到${nodes.size}个按钮，认出目标后点对应设置", Toast.LENGTH_SHORT).show()
        }

        b.btnClearCalib.setOnClickListener {
            prefs.clearCalibration()
            Toast.makeText(this, "已恢复自动识别", Toast.LENGTH_SHORT).show()
        }
    }

    /** 校准列表的一行：节点描述 + 三个设置按钮 */
    private fun addNodeRow(desc: String, svc: DoubaoA11yService) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 10)
        }
        row.addView(TextView(this).apply {
            text = desc
            setTextColor(Color.parseColor("#F5F0E1"))
            textSize = 13f
        })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("设为语音键" to 1, "设为暂停键" to 2, "设为挂断键" to 3).forEach { (label, type) ->
            btns.addView(Button(this, null, 0, com.google.android.material.R.style.Widget_Material3_Button_TextButton).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#FFD76A"))
                setOnClickListener {
                    saveCalibration(desc, type)
                }
            })
        }
        row.addView(btns)
        b.layoutNodes.addView(row)
    }

    /** 把用户选中的节点描述解析为特征保存：格式 text|desc */
    private fun saveCalibration(desc: String, type: Int) {
        // desc形如：「文字」描:描述 位:(x,y)
        val text = desc.substringAfter("「").substringBefore("」")
        val content = desc.substringAfter("描:").substringBefore(" 位:")
        val feature = "$text|$content"
        when (type) {
            1 -> prefs.customCallNode = feature
            2 -> prefs.customPauseNode = feature
            3 -> prefs.customEndNode = feature
        }
        val name = when (type) { 1 -> "语音键"; 2 -> "暂停键"; else -> "挂断键" }
        Toast.makeText(this, "已记住${name}：$text", Toast.LENGTH_LONG).show()
        store.add("校准工具·设定$name", "成功")
    }

    // ─── 操作记录 ───
    private fun setupRecords() {
        b.btnClearRecords.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空记录").setMessage("确定删除全部操作记录吗？")
                .setPositiveButton("清空") { _, _ -> store.clear(); refreshRecords() }
                .setNegativeButton("取消", null).show()
        }
    }

    private fun refreshRecords() {
        val list = store.queryAll()
        b.tvRecordTitle.text = "操作记录（累计通话 ${prefs.totalCalls} 次 · 共 ${list.size} 条）"
        b.layoutRecords.removeAllViews()
        if (list.isEmpty()) {
            b.layoutRecords.addView(TextView(this).apply {
                text = "还没有记录，点一下悬浮球试试"
                setTextColor(Color.parseColor("#9BA3B4")); textSize = 13f
            })
            return
        }
        list.take(50).forEach { r ->
            val tv = TextView(this).apply {
                text = "${r.time}  ${r.action}  ${r.result}${if (r.note.isNotBlank()) "\n备注：${r.note}" else ""}"
                setTextColor(if (r.result.startsWith("成功")) Color.parseColor("#C9D1E0") else Color.parseColor("#F87171"))
                textSize = 12.5f
                setPadding(0, 8, 0, 8)
                setOnClickListener { editRecordNote(r) }
                setOnLongClickListener { confirmDeleteRecord(r); true }
            }
            b.layoutRecords.addView(tv)
        }
    }

    private fun editRecordNote(r: RecordStore.Record) {
        val input = EditText(this).apply {
            setText(r.note); hint = "给这条记录加备注"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("编辑备注").setView(input)
            .setPositiveButton("保存") { _, _ ->
                store.updateNote(r.id, input.text.toString()); refreshRecords()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun confirmDeleteRecord(r: RecordStore.Record) {
        AlertDialog.Builder(this)
            .setTitle("删除这条记录？").setMessage("${r.time} ${r.action}")
            .setPositiveButton("删除") { _, _ -> store.delete(r.id); refreshRecords() }
            .setNegativeButton("取消", null).show()
    }
}
