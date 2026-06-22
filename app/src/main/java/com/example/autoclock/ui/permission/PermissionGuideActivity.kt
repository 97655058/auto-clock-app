package com.example.autoclock.ui.permission

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.autoclock.databinding.ActivityPermissionGuideBinding
import com.example.autoclock.util.AccessibilityUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PermissionGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupButtons() {
        // 无障碍服务
        binding.btnEnableAccessibility.setOnClickListener {
            AccessibilityUtil.openAccessibilitySettings(this)
        }

        // 悬浮窗权限
        binding.btnEnableOverlay.setOnClickListener {
            AccessibilityUtil.openOverlaySettings(this)
        }

        // 电池优化
        binding.btnIgnoreBattery.setOnClickListener {
            AccessibilityUtil.openBatteryOptimizationSettings(this)
        }

        // 完成
        binding.btnDone.setOnClickListener {
            finish()
        }
    }

    private fun updatePermissionStatus() {
        val accessOk = AccessibilityUtil.isAccessibilityServiceEnabled(this)
        val overlayOk = AccessibilityUtil.canDrawOverlays(this)
        val batteryOk = AccessibilityUtil.isIgnoringBatteryOptimizations(this)

        // 无障碍服务状态
        binding.ivStatusAccessibility.setImageResource(
            if (accessOk) android.R.drawable.ic_dialog_info
            else android.R.drawable.ic_dialog_alert
        )
        binding.tvStatusAccessibility.text = if (accessOk) "✅ 已开启" else "❌ 未开启（必须）"
        binding.btnEnableAccessibility.visibility = if (accessOk) View.GONE else View.VISIBLE

        // 悬浮窗权限状态
        binding.ivStatusOverlay.setImageResource(
            if (overlayOk) android.R.drawable.ic_dialog_info
            else android.R.drawable.ic_dialog_alert
        )
        binding.tvStatusOverlay.text = if (overlayOk) "✅ 已授权" else "❌ 未授权（可选）"
        binding.btnEnableOverlay.visibility = if (overlayOk) View.GONE else View.VISIBLE

        // 电池优化状态
        binding.ivStatusBattery.setImageResource(
            if (batteryOk) android.R.drawable.ic_dialog_info
            else android.R.drawable.ic_dialog_alert
        )
        binding.tvStatusBattery.text = if (batteryOk) "✅ 已忽略优化" else "❌ 未忽略（推荐）"
        binding.btnIgnoreBattery.visibility = if (batteryOk) View.GONE else View.VISIBLE

        // 所有必须权限完成才能结束
        binding.btnDone.isEnabled = accessOk
        binding.tvAllGranted.visibility = if (accessOk && batteryOk) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
