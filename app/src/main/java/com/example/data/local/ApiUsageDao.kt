package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiUsageDao {

  @Query(
    """
    SELECT * FROM api_key_usage_stats
    ORDER BY rateLimitHits DESC, successfulRequests DESC, lastUsedAtEpochMs DESC
    """
  )
  fun observeAllKeyStats(): Flow<List<ApiKeyUsageStat>>

  @Query(
    """
    SELECT * FROM api_key_usage_stats
    WHERE provider = :provider
    ORDER BY rateLimitHits DESC, successfulRequests DESC, lastUsedAtEpochMs DESC
    """
  )
  fun observeKeyStatsByProvider(provider: String): Flow<List<ApiKeyUsageStat>>

  @Query("SELECT * FROM api_key_usage_stats WHERE keyId = :keyId LIMIT 1")
  suspend fun getKeyStatById(keyId: String): ApiKeyUsageStat?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertKeyStat(stat: ApiKeyUsageStat)

  @Query("SELECT * FROM api_usage_events ORDER BY recordedAtEpochMs DESC, id DESC LIMIT :limit")
  fun observeRecentEvents(limit: Int = 60): Flow<List<ApiUsageEvent>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertEvent(event: ApiUsageEvent): Long

  @Query(
    """
    DELETE FROM api_usage_events
    WHERE id NOT IN (
      SELECT id FROM api_usage_events ORDER BY recordedAtEpochMs DESC, id DESC LIMIT :keepLimit
    )
    """
  )
  suspend fun trimOldEvents(keepLimit: Int = 120)

  @Query("DELETE FROM api_key_usage_stats WHERE provider = :provider")
  suspend fun deleteKeyStatsByProvider(provider: String)

  @Query("DELETE FROM api_usage_events WHERE provider = :provider")
  suspend fun deleteEventsByProvider(provider: String)

  @Query("DELETE FROM api_key_usage_stats")
  suspend fun deleteAllKeyStats()

  @Query("DELETE FROM api_usage_events")
  suspend fun deleteAllEvents()
}
