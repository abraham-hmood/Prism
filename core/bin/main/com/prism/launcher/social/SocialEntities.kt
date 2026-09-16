package com.prism.launcher.social

import androidx.room.*

@Entity(tableName = "social_posts")
data class SocialPostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: String,
    val authorId: String,
    val authorName: String,
    val authorHandle: String,
    val authorAvatarUrl: String?,
    val content: String,
    val imageUrl: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val likesCount: Int = 0,
    val repostCount: Int = 0,
    val isUserPost: Boolean = false // New flag
)

@Entity(tableName = "social_interactions")
data class SocialInteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: String,
    val actorId: String,        // ID of user or bot who interacted
    val actorName: String,      // Display name for bubbles
    val type: String,           // "like", "share"
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "social_bots")
data class SocialBotEntity(
    @PrimaryKey val botId: String,
    val name: String,
    val handle: String,
    val bio: String,
    val avatarUrl: String?,
    val personaType: String, // e.g. "tech", "vibe", "news"
    val personality: String = "", // Full voice/interests/quirks description — invented once at creation, used to steer every post/comment/DM/reply so this persona reads as a consistent individual instead of a generic assistant.
    val lastPostTime: Long = 0
)

@Entity(tableName = "social_messages")
data class SocialMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val senderId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "social_comments")
data class SocialCommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commentId: String = java.util.UUID.randomUUID().toString(),
    val postId: String,
    // Null for a top-level reply directly on the post; set to another comment's commentId when
    // this is a reply to that comment (a reply to a reply, and so on, forming a tree).
    val parentCommentId: String? = null,
    val authorId: String,
    val authorName: String,
    val authorHandle: String,
    val authorAvatarUrl: String?,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "social_follows")
data class SocialFollowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val botId: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface SocialDao {
    @Query("SELECT * FROM social_posts ORDER BY timestamp DESC")
    suspend fun getAllPosts(): List<SocialPostEntity>

    @Query("SELECT * FROM social_posts WHERE authorId = :botId ORDER BY timestamp DESC")
    suspend fun getPostsByAuthor(botId: String): List<SocialPostEntity>

    @Query("SELECT * FROM social_posts WHERE postId = :postId")
    suspend fun getPostById(postId: String): SocialPostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPost(post: SocialPostEntity)

    @Query("SELECT * FROM social_bots")
    suspend fun getAllBots(): List<SocialBotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBot(bot: SocialBotEntity)

    @Query("SELECT * FROM social_bots WHERE botId = :id")
    suspend fun getBot(id: String): SocialBotEntity?

    // Comments
    @Query("SELECT * FROM social_comments WHERE postId = :postId AND parentCommentId IS NULL ORDER BY timestamp ASC")
    suspend fun getCommentsForPost(postId: String): List<SocialCommentEntity>

    @Query("SELECT * FROM social_comments WHERE parentCommentId = :parentCommentId ORDER BY timestamp ASC")
    suspend fun getReplies(parentCommentId: String): List<SocialCommentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComment(comment: SocialCommentEntity)

    // Interactions
    @Query("SELECT * FROM social_interactions WHERE postId = :postId AND type = :type")
    suspend fun getInteractions(postId: String, type: String): List<SocialInteractionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInteraction(interaction: SocialInteractionEntity)

    // Messages
    @Query("SELECT * FROM social_messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    suspend fun getMessagesForChat(chatId: String): List<SocialMessageEntity>

    @Query("SELECT * FROM social_messages GROUP BY chatId ORDER BY timestamp DESC")
    suspend fun getRecentChats(): List<SocialMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(msg: SocialMessageEntity)

    // Follows
    @Query("SELECT COUNT(*) FROM social_follows WHERE botId = :botId")
    suspend fun isFollowing(botId: String): Boolean

    // -- Mesh federation (NebulaMeshSync) -----------------------------------------------------
    // Everything here is `suspend` because :core is a non-Android source set, where Room allows
    // nothing else in a DAO. These add no tables and no columns -- only queries -- so the database
    // version stays at 11 and no user's existing feed is touched. (See AppDatabase's doc comment:
    // bumping the version would meet fallbackToDestructiveMigration and silently wipe their data,
    // which is why federation de-duplicates with presence checks rather than a unique index.)

    @Query("SELECT * FROM social_posts ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentPosts(limit: Int): List<SocialPostEntity>

    @Query("SELECT * FROM social_comments ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentComments(limit: Int): List<SocialCommentEntity>

    @Query("SELECT COUNT(*) FROM social_posts")
    suspend fun countPosts(): Int

    /** The comment-side twin of the existing [getPostById]; postId/commentId are UUIDs, so a
     * presence check is enough to recognise something a peer has already sent us. */
    @Query("SELECT * FROM social_comments WHERE commentId = :commentId LIMIT 1")
    suspend fun getCommentById(commentId: String): SocialCommentEntity?

    // IGNORE, not REPLACE: a federated pull must never overwrite a local row with a peer's copy of
    // it. The plain insert* functions above keep REPLACE for local writes, where that is correct.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPostIfNew(post: SocialPostEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCommentIfNew(comment: SocialCommentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBotIfNew(bot: SocialBotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun follow(follow: SocialFollowEntity)

    @Query("DELETE FROM social_follows WHERE botId = :botId")
    suspend fun unfollow(botId: String)
}
