package com.example.autoclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import com.example.autoclock.data.ClockTaskRepository
import com.example.autoclock.data.model.TriggerType
import com.example.autoclock.worker.WorkManagerScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wi-Fi 状态广播接收器
 * 检测到指定 Wi-Fi 连接时，触发对应打卡任务
 */
@AndroidEntryPoint
class WifiReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: ClockTaskRepository

    companion object {
        private const val TAG = "WifiReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return

        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val currentSsid = wifiInfo.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: return

        if (currentSsid.isBlank() || currentSsid == "<unknown ssid>") return

        Log.i(TAG, "onReceive: 当前 Wi-Fi SSID=$currentSsid")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tasks = repository.getEnabledTasks()
                tasks.filter { task ->
                    task.triggerType == TriggerType.WIFI &&
                            task.wifiSsid.equals(currentSsid, ignoreCase = true)
                }.forEach { task ->
                    Log.i(TAG, "触发 Wi-Fi 打卡任务: ${task.name}")
                    WorkManagerScheduler.runTaskImmediately(context, task.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Wi-Fi 触发异常: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
