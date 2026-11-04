package com.junge.doubaoball

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 自动更新：打开App时静默检查服务器版本
 * 有新版本 → 弹窗显示更新内容 → 确认后系统下载器下载 → 自动跳安装（覆盖安装，数据不丢）
 *
 * 服务器约定：
 *   https://ecojia.cn/doubao-ball/version.json  {"versionCode":3,"versionName":"1.2.0","apkUrl":"...","changelog":"..."}
 */
object UpdateChecker {

    private const val VERSION_URL = "https://ecojia.cn/doubao-ball/version.json"

    /** 静默检查（启动时调用）：失败不打扰用户 */
    fun check(context: Context) {
        thread {
            try {
                val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.useCaches = false
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)
                val remoteCode = json.getInt("versionCode")
                val remoteName = json.optString("versionName", "")
                val apkUrl = json.getString("apkUrl")
                val changelog = json.optString("changelog", "体验优化")

                val localCode = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionCode

                if (remoteCode > localCode) {
                    (context as? MainActivity)?.runOnUiThread {
                        showUpdateDialog(context, remoteName, apkUrl, changelog)
                    }
                }
            } catch (e: Exception) {
                // 静默失败：服务器没连上不影响正常使用
            }
        }
    }

    private fun showUpdateDialog(context: Context, versionName: String, apkUrl: String, changelog: String) {
        AlertDialog.Builder(context)
            .setTitle("发现新版本 v$versionName")
            .setMessage("更新内容：\n$changelog\n\n点「立即更新」自动下载安装，不用删旧版。")
            .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(context, apkUrl) }
            .setNegativeButton("下次再说", null)
            .show()
    }

    /** 用系统下载器下载APK，完成后自动调起安装 */
    private fun downloadAndInstall(context: Context, apkUrl: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("豆包悬浮球 新版本")
            setDescription("正在下载更新包…")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "doubao-ball-update.apk")
            setMimeType("application/vnd.android.package-archive")
        }
        val downloadId = dm.enqueue(request)
        Toast.makeText(context, "开始下载，完成后会自动弹出安装", Toast.LENGTH_LONG).show()

        // 监听下载完成 → 自动跳安装界面
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                val uri = dm.getUriForDownloadedFile(downloadId) ?: return
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(install)
                runCatching { ctx.unregisterReceiver(this) }
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), flags)
        } else {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }
}
