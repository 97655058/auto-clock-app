package com.example.autoclock.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.autoclock.data.model.ClockAction
import com.example.autoclock.data.model.ClockTask
import com.example.autoclock.data.model.TriggerType
import com.example.autoclock.data.model.toWorkdaysLabel
import com.example.autoclock.databinding.ItemTaskBinding
import java.text.SimpleDateFormat
import java.util.*

class TaskAdapter(
    private val onToggle: (ClockTask, Boolean) -> Unit,
    private val onEdit: (ClockTask) -> Unit,
    private val onDelete: (ClockTask) -> Unit,
    private val onRunNow: (ClockTask) -> Unit
) : ListAdapter<ClockTask, TaskAdapter.TaskViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ClockTask>() {
            override fun areItemsTheSame(oldItem: ClockTask, newItem: ClockTask) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ClockTask, newItem: ClockTask) =
                oldItem == newItem
        }
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    }

    inner class TaskViewHolder(private val binding: ItemTaskBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(task: ClockTask) {
            binding.apply {
                tvTaskName.text = task.name

                // 触发条件描述
                tvTriggerDesc.text = when (task.triggerType) {
                    TriggerType.SCHEDULED ->
                        "定时：${task.triggerHour.toString().padStart(2, '0')}:" +
                                "${task.triggerMinute.toString().padStart(2, '0')} " +
                                task.workdays.toWorkdaysLabel()
                    TriggerType.WIFI -> "Wi-Fi：${task.wifiSsid}"
                }

                // 打卡动作
                tvClockAction.text = when (task.clockAction) {
                    ClockAction.CLOCK_IN -> "⬆ 上班打卡"
                    ClockAction.CLOCK_OUT -> "⬇ 下班打卡"
                }

                // 最后执行结果
                if (task.lastResult != null && task.lastRunAt != null) {
                    tvLastResult.text =
                        "上次：${timeFormat.format(Date(task.lastRunAt))} ${task.lastResult}"
                } else {
                    tvLastResult.text = "尚未执行"
                }

                // 开关
                switchEnabled.isChecked = task.enabled
                switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(task, isChecked)
                }

                // 编辑
                btnEdit.setOnClickListener { onEdit(task) }
                // 删除
                btnDelete.setOnClickListener { onDelete(task) }
                // 立即执行
                btnRunNow.setOnClickListener { onRunNow(task) }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemTaskBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
