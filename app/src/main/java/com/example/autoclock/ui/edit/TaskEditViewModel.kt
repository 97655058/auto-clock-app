package com.example.autoclock.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autoclock.data.ClockTaskRepository
import com.example.autoclock.data.model.ClockAction
import com.example.autoclock.data.model.ClockTask
import com.example.autoclock.data.model.TriggerType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TaskEditUiState(
    val taskId: Long = 0L,
    val name: String = "",
    val enabled: Boolean = true,
    val triggerType: TriggerType = TriggerType.SCHEDULED,
    val triggerHour: Int = 9,
    val triggerMinute: Int = 0,
    val workdays: Int = 0b0011111, // 周一到周五
    val wifiSsid: String = "",
    val clockAction: ClockAction = ClockAction.CLOCK_IN,
    val isSaving: Boolean = false,
    val savedSuccess: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class TaskEditViewModel @Inject constructor(
    private val repository: ClockTaskRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskEditUiState())
    val uiState: StateFlow<TaskEditUiState> = _uiState.asStateFlow()

    init {
        val taskId = savedStateHandle.get<Long>("task_id") ?: 0L
        if (taskId > 0) {
            loadTask(taskId)
        }
    }

    private fun loadTask(taskId: Long) {
        viewModelScope.launch {
            val task = repository.getTaskById(taskId) ?: return@launch
            _uiState.value = TaskEditUiState(
                taskId = task.id,
                name = task.name,
                enabled = task.enabled,
                triggerType = task.triggerType,
                triggerHour = task.triggerHour,
                triggerMinute = task.triggerMinute,
                workdays = task.workdays,
                wifiSsid = task.wifiSsid,
                clockAction = task.clockAction
            )
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name, errorMessage = null)
    }

    fun updateTriggerType(type: TriggerType) {
        _uiState.value = _uiState.value.copy(triggerType = type)
    }

    fun updateTime(hour: Int, minute: Int) {
        _uiState.value = _uiState.value.copy(triggerHour = hour, triggerMinute = minute)
    }

    fun toggleWorkday(dayIndex: Int) {
        val current = _uiState.value.workdays
        val toggled = current xor (1 shl dayIndex)
        _uiState.value = _uiState.value.copy(workdays = toggled)
    }

    fun updateWifiSsid(ssid: String) {
        _uiState.value = _uiState.value.copy(wifiSsid = ssid)
    }

    fun updateClockAction(action: ClockAction) {
        _uiState.value = _uiState.value.copy(clockAction = action)
    }

    fun saveTask() {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.value = state.copy(errorMessage = "请输入任务名称")
            return
        }
        if (state.triggerType == TriggerType.WIFI && state.wifiSsid.isBlank()) {
            _uiState.value = state.copy(errorMessage = "请输入 Wi-Fi 名称")
            return
        }

        _uiState.value = state.copy(isSaving = true)
        viewModelScope.launch {
            runCatching {
                repository.saveTask(
                    ClockTask(
                        id = state.taskId,
                        name = state.name.trim(),
                        enabled = state.enabled,
                        triggerType = state.triggerType,
                        triggerHour = state.triggerHour,
                        triggerMinute = state.triggerMinute,
                        workdays = state.workdays,
                        wifiSsid = state.wifiSsid.trim(),
                        clockAction = state.clockAction
                    )
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(isSaving = false, savedSuccess = true)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = e.message ?: "保存失败"
                )
            }
        }
    }
}
