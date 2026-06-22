package com.example.autoclock.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.autoclock.databinding.ActivityMainBinding
import com.example.autoclock.ui.edit.TaskEditActivity
import com.example.autoclock.ui.permission.PermissionGuideActivity
import com.example.autoclock.util.AccessibilityUtil
import com.example.autoclock.worker.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TaskListViewModel by viewModels()
    private lateinit var adapter: TaskAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupFab()
        observeTasks()

        // 首次检查权限
        checkPermissionsOnFirstLaunch()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupRecyclerView() {
        adapter = TaskAdapter(
            onToggle = { task, enabled ->
                viewModel.setTaskEnabled(task.id, enabled)
                if (enabled) {
                    AlarmScheduler.scheduleTask(this, task.copy(enabled = true))
                } else {
                    AlarmScheduler.cancelTask(this, task.id)
                }
            },
            onEdit = { task ->
                startActivity(
                    Intent(this, TaskEditActivity::class.java).apply {
                        putExtra("task_id", task.id)
                    }
                )
            },
            onDelete = { task ->
                AlertDialog.Builder(this)
                    .setTitle("删除任务")
                    .setMessage("确定删除「${task.name}」吗？")
                    .setPositiveButton("删除") { _, _ ->
                        viewModel.deleteTask(task)
                        AlarmScheduler.cancelTask(this, task.id)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            },
            onRunNow = { task ->
                AlarmScheduler.runTaskImmediately(this, task)
                showSnackbar("已触发「${task.name}」立即执行")
            }
        )
        binding.recyclerTasks.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    private fun setupFab() {
        binding.fabAddTask.setOnClickListener {
            startActivity(Intent(this, TaskEditActivity::class.java))
        }
    }

    private fun observeTasks() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.tasks.collect { tasks ->
                    adapter.submitList(tasks)
                    binding.tvEmpty.visibility =
                        if (tasks.isEmpty()) android.view.View.VISIBLE
                        else android.view.View.GONE
                }
            }
        }
    }

    private fun updatePermissionStatus() {
        val accessEnabled = AccessibilityUtil.isAccessibilityServiceEnabled(this)
        val batteryOk = AccessibilityUtil.isIgnoringBatteryOptimizations(this)

        if (!accessEnabled || !batteryOk) {
            binding.bannerPermission.visibility = android.view.View.VISIBLE
            binding.bannerPermission.setOnClickListener {
                startActivity(Intent(this, PermissionGuideActivity::class.java))
            }
        } else {
            binding.bannerPermission.visibility = android.view.View.GONE
        }
    }

    private fun checkPermissionsOnFirstLaunch() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("first_launch", true)
        if (isFirstLaunch) {
            prefs.edit().putBoolean("first_launch", false).apply()
            startActivity(Intent(this, PermissionGuideActivity::class.java))
        }
    }

    private fun showSnackbar(msg: String) {
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
            .show()
    }
}
