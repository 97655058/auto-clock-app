package com.example.autoclock.ui.edit

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.autoclock.R
import com.example.autoclock.data.model.ClockAction
import com.example.autoclock.data.model.TriggerType
import com.example.autoclock.databinding.ActivityTaskEditBinding
import com.example.autoclock.ui.main.MainActivity
import com.example.autoclock.worker.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TaskEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskEditBinding
    private val viewModel: TaskEditViewModel by viewModels()

    private val dayCheckboxes by lazy {
        listOf(
            binding.cbMon, binding.cbTue, binding.cbWed,
            binding.cbThu, binding.cbFri, binding.cbSat, binding.cbSun
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupUi()
        observeUiState()
    }

    private fun setupUi() {
        // 触发类型切换
        binding.rgTriggerType.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                R.id.rb_trigger_scheduled -> TriggerType.SCHEDULED
                R.id.rb_trigger_wifi -> TriggerType.WIFI
                else -> TriggerType.SCHEDULED
            }
            viewModel.updateTriggerType(type)
        }

        // 时间选择
        binding.btnPickTime.setOnClickListener {
            val state = viewModel.uiState.value
            TimePickerDialog(
                this,
                { _, h, m -> viewModel.updateTime(h, m) },
                state.triggerHour,
                state.triggerMinute,
                true
            ).show()
        }

        // 工作日勾选
        dayCheckboxes.forEachIndexed { index, cb ->
            cb.setOnCheckedChangeListener { _, _ ->
                viewModel.toggleWorkday(index)
            }
        }

        // 打卡动作
        binding.rgClockAction.setOnCheckedChangeListener { _, checkedId ->
            val action = when (checkedId) {
                R.id.rb_clock_in -> ClockAction.CLOCK_IN
                R.id.rb_clock_out -> ClockAction.CLOCK_OUT
                else -> ClockAction.CLOCK_IN
            }
            viewModel.updateClockAction(action)
        }

        // Wi-Fi SSID 输入
        binding.etWifiSsid.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.updateWifiSsid(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 任务名称输入
        binding.etTaskName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.updateName(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 保存按钮
        binding.btnSave.setOnClickListener {
            viewModel.saveTask()
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 任务名称（避免循环触发）
                    if (binding.etTaskName.text.toString() != state.name) {
                        binding.etTaskName.setText(state.name)
                    }

                    // 触发类型
                    when (state.triggerType) {
                        TriggerType.SCHEDULED -> {
                            binding.rbTriggerScheduled.isChecked = true
                            binding.layoutScheduled.visibility = View.VISIBLE
                            binding.layoutWifi.visibility = View.GONE
                        }
                        TriggerType.WIFI -> {
                            binding.rbTriggerWifi.isChecked = true
                            binding.layoutScheduled.visibility = View.GONE
                            binding.layoutWifi.visibility = View.VISIBLE
                        }
                    }

                    // 时间显示
                    binding.btnPickTime.text =
                        "${state.triggerHour.toString().padStart(2, '0')}:" +
                                state.triggerMinute.toString().padStart(2, '0')

                    // 工作日勾选（同步 UI 状态）
                    dayCheckboxes.forEachIndexed { index, cb ->
                        val shouldCheck = (state.workdays shr index) and 1 == 1
                        if (cb.isChecked != shouldCheck) cb.isChecked = shouldCheck
                    }

                    // Wi-Fi SSID
                    if (binding.etWifiSsid.text.toString() != state.wifiSsid) {
                        binding.etWifiSsid.setText(state.wifiSsid)
                    }

                    // 打卡动作
                    when (state.clockAction) {
                        ClockAction.CLOCK_IN -> binding.rbClockIn.isChecked = true
                        ClockAction.CLOCK_OUT -> binding.rbClockOut.isChecked = true
                    }

                    // 错误提示
                    binding.tilTaskName.error = if (state.errorMessage != null &&
                        state.errorMessage.contains("名称")
                    ) state.errorMessage else null
                    binding.tilWifiSsid.error = if (state.errorMessage != null &&
                        state.errorMessage.contains("Wi-Fi")
                    ) state.errorMessage else null

                    // 保存成功，重新调度任务并返回
                    if (state.savedSuccess) {
                        // 重新调度
                        state.let { s ->
                            if (s.taskId > 0) {
                                val task = com.example.autoclock.data.model.ClockTask(
                                    id = s.taskId,
                                    name = s.name,
                                    enabled = s.enabled,
                                    triggerType = s.triggerType,
                                    triggerHour = s.triggerHour,
                                    triggerMinute = s.triggerMinute,
                                    workdays = s.workdays,
                                    wifiSsid = s.wifiSsid,
                                    clockAction = s.clockAction
                                )
                                AlarmScheduler.scheduleTask(this@TaskEditActivity, task)
                            }
                        }
                        finish()
                    }

                    // 加载状态
                    binding.btnSave.isEnabled = !state.isSaving
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
