package com.example.autoclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * 开机广播接收器
 * 设备重启后重新注册所有已启用任务的 WorkManager 调度
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: ClockTaskRepository

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        Log.i(TAG, "onReceive: 收到开机广播，重新调度所有任务")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tasks = repository.getEnabledTasks()
                tasks.filter { it.triggerType == TriggerType.SCHEDULED }
                    .forEach { task ->
                        WorkManagerScheduler.scheduleTask(context, task)
                        Log.i(TAG, "重新调度任务: ${task.name}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "重新调度失败: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
