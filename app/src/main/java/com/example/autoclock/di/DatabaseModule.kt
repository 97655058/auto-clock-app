package com.example.autoclock.di

import android.content.Context
import androidx.room.Room
import com.example.autoclock.data.db.AutoClockDatabase
import com.example.autoclock.data.db.ClockTaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AutoClockDatabase {
        return Room.databaseBuilder(
            context,
            AutoClockDatabase::class.java,
            AutoClockDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideClockTaskDao(database: AutoClockDatabase): ClockTaskDao {
        return database.clockTaskDao()
    }
}
