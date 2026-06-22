package com.example.autoclock.data.db

import androidx.room.TypeConverter
import com.example.autoclock.data.model.ClockAction
import com.example.autoclock.data.model.TriggerType

class Converters {
    @TypeConverter
    fun fromTriggerType(value: TriggerType): String = value.name

    @TypeConverter
    fun toTriggerType(value: String): TriggerType = TriggerType.valueOf(value)

    @TypeConverter
    fun fromClockAction(value: ClockAction): String = value.name

    @TypeConverter
    fun toClockAction(value: String): ClockAction = ClockAction.valueOf(value)
}
