package com.example.autoclock.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object AccessibilityUtil {

    /**
     * 检查自动打卡无障碍服务是否已开启
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val targetServiceName = "${context.packageName}/" +
                "com.example.autoclock.service.AutoClockAccessibilityService"
        return enabledServices.any { info ->
            info.resolveInfo.serviceInfo.let { si ->
                "${si.packageName}/${si.name}" == targetServiceName
            }
        }
    }

    /**
     * 跳转到系统无障碍设置页
     */
    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * 检查悬浮窗权限
     */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * 跳转到悬浮窗权限设置
     */
    fun openOverlaySettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    /**
     * 检查是否已忽略电池优化
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 跳转到电池优化设置
     */
    fun openBatteryOptimizationSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}
