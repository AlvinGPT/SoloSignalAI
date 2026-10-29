package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "safety_events")
data class SafetyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val eventGroupUuid: String = UUID.randomUUID().toString(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val eventType: String,
    val title: String,
    val outcomeSummary: String,
    val deliveryStatus: String,
    val sensorDetails: String = "",
    val isSyntheticTest: Boolean = false
) {
    constructor(
        timestampEpochMillis: Long,
        eventType: String,
        title: String,
        details: String,
        channelUsed: String,
        deliveryOutcome: String
    ) : this(
        id = 0L,
        eventGroupUuid = UUID.randomUUID().toString(),
        timestampMillis = timestampEpochMillis,
        eventType = eventType,
        title = title,
        outcomeSummary = details,
        deliveryStatus = deliveryOutcome.ifBlank { channelUsed },
        sensorDetails = if (deliveryOutcome.isNotBlank() && channelUsed.isNotBlank()) "Channel: $channelUsed" else "",
        isSyntheticTest = eventType == "TEST_ALERT_TRIGGERED"
    )
}

typealias EventLogEntity = SafetyEventEntity

@Dao
interface SafetyEventDao {
    @Query("SELECT * FROM safety_events ORDER BY timestampMillis DESC")
    fun observeAllEvents(): Flow<List<SafetyEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SafetyEventEntity): Long

    @Query("DELETE FROM safety_events WHERE id = :id")
    suspend fun deleteEventById(id: Long)

    @Query("DELETE FROM safety_events")
    suspend fun clearAllEvents()
}

@Database(entities = [SafetyEventEntity::class], version = 1, exportSchema = false)
abstract class EventHistoryDatabase : RoomDatabase() {
    abstract fun safetyEventDao(): SafetyEventDao

    fun eventLogDao(): SafetyEventDao = safetyEventDao()

    companion object {
        @Volatile
        private var INSTANCE: EventHistoryDatabase? = null

        fun getInstance(context: Context): EventHistoryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EventHistoryDatabase::class.java,
                    "solosignal_event_history.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
