package com.example.autoclock.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 触发类型枚举
 */
enum class TriggerType {
    /** 定时触发（工作日指定时间） */
    SCHEDULED,
    /** 连接指定 Wi-Fi 时触发 */
    WIFI
}

/**
 * 打卡动作枚举（对应钉钉考勤打卡按钮）
 */
enum class ClockAction {
    /** 上班打卡 */
    CLOCK_IN,
    /** 下班打卡 */
    CLOCK_OUT
}

/**
 * 打卡任务实体
 *
 * @param id            主键，自增
 * @param name          任务名称，如"上班打卡"
 * @param enabled       是否启用
 * @param triggerType   触发类型
 * @param triggerHour   定时触发：小时（0-23）
 * @param triggerMinute 定时触发：分钟（0-59）
 * @param workdays      定时触发：工作日掩码（bit0=周一, bit1=周二, ..., bit6=周日）
 * @param wifiSsid      Wi-Fi 触发：SSID 名称
 * @param clockAction   打卡动作
 * @param createdAt     创建时间（时间戳毫秒）
 * @param updatedAt     最后更新时间（时间戳毫秒）
 * @param lastResult    最后执行结果（"成功" / "失败: ..." / null 未执行）
 * @param lastRunAt     最后执行时间（时间戳毫秒）
 */
@Entity(tableName = "clock_tasks")
data class ClockTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val triggerType: TriggerType = TriggerType.SCHEDULED,
    val triggerHour: Int = 9,
    val triggerMinute: Int = 0,
    /** 工作日掩码：默认周一到周五 = 0b0011111 = 31 */
    val workdays: Int = 0b0011111,
    val wifiSsid: String = "",
    val clockAction: ClockAction = ClockAction.CLOCK_IN,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastResult: String? = null,
    val lastRunAt: Long? = null
)

/**
 * 工作日掩码辅助方法
 */
fun Int.isDayEnabled(dayIndex: Int): Boolean = (this shr dayIndex) and 1 == 1

fun Int.toggleDay(dayIndex: Int): Int = this xor (1 shl dayIndex)

fun Int.setDay(dayIndex: Int, enabled: Boolean): Int =
    if (enabled) this or (1 shl dayIndex) else this and (1 shl dayIndex).inv()

/** 返回工作日显示文本，如 "周一 周二 周三" */
fun Int.toWorkdaysLabel(): String {
    val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    return days.filterIndexed { index, _ -> isDayEnabled(index) }.joinToString(" ")
}
