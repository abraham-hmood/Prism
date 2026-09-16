package com.prism.launcher.messaging

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ai_messages")
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val isSent: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentUri: String? = null,
    val attachmentType: String? = null // "image" or "video"
)

@Dao
interface AiMessageDao {
    @Query("SELECT * FROM ai_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<AiMessageEntity>>

    @Insert
    suspend fun insert(message: AiMessageEntity)

    @Query("DELETE FROM ai_messages")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM ai_messages WHERE text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMessages(query: String): List<AiMessageEntity>
}
