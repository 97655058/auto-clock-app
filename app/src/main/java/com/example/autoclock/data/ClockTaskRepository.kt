package com.example.autoclock.data

import com.example.autoclock.data.db.ClockTaskDao
import com.example.autoclock.data.model.ClockTask
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClockTaskRepository @Inject constructor(
    private val dao: ClockTaskDao
) {

    fun getAllTasksFlow(): Flow<List<ClockTask>> = dao.getAllTasksFlow()

    suspend fun getEnabledTasks(): List<ClockTask> = dao.getEnabledTasks()

    suspend fun getTaskById(id: Long): ClockTask? = dao.getTaskById(id)

    suspend fun saveTask(task: ClockTask): Long {
        return if (task.id == 0L) {
            dao.insertTask(task)
        } else {
            dao.updateTask(task.copy(updatedAt = System.currentTimeMillis()))
            task.id
        }
    }

    suspend fun deleteTask(task: ClockTask) = dao.deleteTask(task)

    suspend fun deleteTaskById(id: Long) = dao.deleteTaskById(id)

    suspend fun setTaskEnabled(id: Long, enabled: Boolean) =
        dao.setTaskEnabled(id, enabled)

    suspend fun updateLastResult(id: Long, result: String) =
        dao.updateLastResult(id, result)
}
