package com.example.autoclock.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.autoclock.data.model.ClockTask

@Database(
    entities = [ClockTask::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AutoClockDatabase : RoomDatabase() {

    abstract fun clockTaskDao(): ClockTaskDao

    companion object {
        const val DATABASE_NAME = "auto_clock_db"
    }
}
