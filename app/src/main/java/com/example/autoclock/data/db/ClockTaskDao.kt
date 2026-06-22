package com.example.autoclock.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.autoclock.data.model.ClockTask
import kotlinx.coroutines.flow.Flow

@Dao
interface ClockTaskDao {

    @Query("SELECT * FROM clock_tasks ORDER BY createdAt DESC")
    fun getAllTasksFlow(): Flow<List<ClockTask>>

    @Query("SELECT * FROM clock_tasks ORDER BY createdAt DESC")
    fun getAllTasksLiveData(): LiveData<List<ClockTask>>

    @Query("SELECT * FROM clock_tasks WHERE enabled = 1")
    suspend fun getEnabledTasks(): List<ClockTask>

    @Query("SELECT * FROM clock_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): ClockTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ClockTask): Long

    @Update
    suspend fun updateTask(task: ClockTask)

    @Delete
    suspend fun deleteTask(task: ClockTask)

    @Query("DELETE FROM clock_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query("UPDATE clock_tasks SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setTaskEnabled(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE clock_tasks SET lastResult = :result, lastRunAt = :runAt WHERE id = :id")
    suspend fun updateLastResult(id: Long, result: String, runAt: Long = System.currentTimeMillis())
}
