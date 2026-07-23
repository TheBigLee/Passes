package ch.bigli.passes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PassDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PassEntity)

    @Query("SELECT * FROM passes ORDER BY relevantDateEpoch IS NULL, relevantDateEpoch ASC, subtitle ASC")
    fun observeAll(): Flow<List<PassEntity>>

    @Query("SELECT * FROM passes WHERE id = :id")
    suspend fun getById(id: String): PassEntity?

    @Query("DELETE FROM passes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE passes SET voided = 1 WHERE id = :id")
    suspend fun markVoided(id: String)

    @Query("UPDATE passes SET autoUpdateEnabled = :enabled WHERE id = :id")
    suspend fun setAutoUpdateEnabled(id: String, enabled: Boolean)
}
