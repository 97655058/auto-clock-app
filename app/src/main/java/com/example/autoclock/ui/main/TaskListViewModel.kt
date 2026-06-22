package com.example.autoclock.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autoclock.data.ClockTaskRepository
import com.example.autoclock.data.model.ClockTask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val repository: ClockTaskRepository
) : ViewModel() {

    val tasks: StateFlow<List<ClockTask>> = repository.getAllTasksFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setTaskEnabled(taskId: Long, enabled: Boolean) {
        viewModelScope.launch {
            repository.setTaskEnabled(taskId, enabled)
        }
    }

    fun deleteTask(task: ClockTask) {
        viewModelScope.launch {
            repository.deleteTask(task)
        }
    }
}
