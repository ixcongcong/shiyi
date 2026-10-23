package cn.shiyi.reminder.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY createdAt DESC")
    fun observeReminders(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM occurrences WHERE state != 'CANCELLED' ORDER BY plannedAt DESC")
    fun observeOccurrences(): Flow<List<OccurrenceEntity>>
    @Query("SELECT * FROM reminders")
    suspend fun reminders(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun reminder(id: String): ReminderEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(reminder: ReminderEntity)
    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: String)
    @Query("SELECT * FROM occurrences WHERE id = :id")
    suspend fun occurrence(id: Long): OccurrenceEntity?
    @Query("SELECT * FROM occurrences")
    suspend fun occurrences(): List<OccurrenceEntity>
    @Query("SELECT * FROM occurrences WHERE reminderId = :reminderId AND state IN ('PENDING', 'ALERTING')")
    suspend fun activeFor(reminderId: String): List<OccurrenceEntity>
    @Query("SELECT * FROM occurrences WHERE state = 'PENDING' OR (state = 'ALERTING' AND ringAt > 0)")
    suspend fun scheduled(): List<OccurrenceEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(occurrence: OccurrenceEntity): Long
    @androidx.room.Update
    suspend fun update(occurrence: OccurrenceEntity)
    @Query("DELETE FROM occurrences WHERE state = 'CANCELLED' AND notifiedAt IS NULL")
    suspend fun pruneCancelled()
    @Query("DELETE FROM occurrences")
    suspend fun clearOccurrences()
    @Query("DELETE FROM reminders")
    suspend fun clearReminders()
}

@Database(entities = [ReminderEntity::class, OccurrenceEntity::class], version = 1, exportSchema = true)
abstract class ShiyiDatabase : RoomDatabase() {
    abstract fun reminders(): ReminderDao
}
