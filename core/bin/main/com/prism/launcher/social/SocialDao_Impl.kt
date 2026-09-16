package com.prism.launcher.social

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class SocialDao_Impl(
  __db: RoomDatabase,
) : SocialDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfSocialPostEntity: EntityInsertAdapter<SocialPostEntity>

  private val __insertAdapterOfSocialBotEntity: EntityInsertAdapter<SocialBotEntity>

  private val __insertAdapterOfSocialCommentEntity: EntityInsertAdapter<SocialCommentEntity>

  private val __insertAdapterOfSocialInteractionEntity: EntityInsertAdapter<SocialInteractionEntity>

  private val __insertAdapterOfSocialMessageEntity: EntityInsertAdapter<SocialMessageEntity>

  private val __insertAdapterOfSocialPostEntity_1: EntityInsertAdapter<SocialPostEntity>

  private val __insertAdapterOfSocialCommentEntity_1: EntityInsertAdapter<SocialCommentEntity>

  private val __insertAdapterOfSocialBotEntity_1: EntityInsertAdapter<SocialBotEntity>

  private val __insertAdapterOfSocialFollowEntity: EntityInsertAdapter<SocialFollowEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfSocialPostEntity = object : EntityInsertAdapter<SocialPostEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `social_posts` (`id`,`postId`,`authorId`,`authorName`,`authorHandle`,`authorAvatarUrl`,`content`,`imageUrl`,`timestamp`,`likesCount`,`repostCount`,`isUserPost`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: SocialPostEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.postId)
        statement.bindText(3, entity.authorId)
        statement.bindText(4, entity.authorName)
        statement.bindText(5, entity.authorHandle)
        val _tmpAuthorAvatarUrl: String? = entity.authorAvatarUrl
        if (_tmpAuthorAvatarUrl == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpAuthorAvatarUrl)
        }
        statement.bindText(7, entity.content)
        val _tmpImageUrl: String? = entity.imageUrl
        if (_tmpImageUrl == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpImageUrl)
        }
        statement.bindLong(9, entity.timestamp)
        statement.bindLong(10, entity.likesCount.toLong())
        statement.bindLong(11, entity.repostCount.toLong())
        val _tmp: Int = if (entity.isUserPost) 1 else 0
        statement.bindLong(12, _tmp.toLong())
      }
    }
    this.__insertAdapterOfSocialBotEntity = object : EntityInsertAdapter<SocialBotEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `social_bots` (`botId`,`name`,`handle`,`bio`,`avatarUrl`,`personaType`,`personality`,`lastPostTime`) VALUES (?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: SocialBotEntity) {
        statement.bindText(1, entity.botId)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.handle)
        statement.bindText(4, entity.bio)
        val _tmpAvatarUrl: String? = entity.avatarUrl
        if (_tmpAvatarUrl == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpAvatarUrl)
        }
        statement.bindText(6, entity.personaType)
        statement.bindText(7, entity.personality)
        statement.bindLong(8, entity.lastPostTime)
      }
    }
    this.__insertAdapterOfSocialCommentEntity = object : EntityInsertAdapter<SocialCommentEntity>()
        {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `social_comments` (`id`,`commentId`,`postId`,`parentCommentId`,`authorId`,`authorName`,`authorHandle`,`authorAvatarUrl`,`content`,`timestamp`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: SocialCommentEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.commentId)
        statement.bindText(3, entity.postId)
        val _tmpParentCommentId: String? = entity.parentCommentId
        if (_tmpParentCommentId == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpParentCommentId)
        }
        statement.bindText(5, entity.authorId)
        statement.bindText(6, entity.authorName)
        statement.bindText(7, entity.authorHandle)
        val _tmpAuthorAvatarUrl: String? = entity.authorAvatarUrl
        if (_tmpAuthorAvatarUrl == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpAuthorAvatarUrl)
        }
        statement.bindText(9, entity.content)
        statement.bindLong(10, entity.timestamp)
      }
    }
    this.__insertAdapterOfSocialInteractionEntity = object :
        EntityInsertAdapter<SocialInteractionEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `social_interactions` (`id`,`postId`,`actorId`,`actorName`,`type`,`timestamp`) VALUES (nullif(?, 0),?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: SocialInteractionEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.postId)
        statement.bindText(3, entity.actorId)
        statement.bindText(4, entity.actorName)
        statement.bindText(5, entity.type)
        statement.bindLong(6, entity.timestamp)
      }
    }
    this.__insertAdapterOfSocialMessageEntity = object : EntityInsertAdapter<SocialMessageEntity>()
        {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `social_messages` (`id`,`chatId`,`senderId`,`content`,`timestamp`) VALUES (nullif(?, 0),?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: SocialMessageEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.chatId)
        statement.bindText(3, entity.senderId)
        statement.bindText(4, entity.content)
        statement.bindLong(5, entity.timestamp)
      }
    }
    this.__insertAdapterOfSocialPostEntity_1 = object : EntityInsertAdapter<SocialPostEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR IGNORE INTO `social_posts` (`id`,`postId`,`authorId`,`authorName`,`authorHandle`,`authorAvatarUrl`,`content`,`imageUrl`,`timestamp`,`likesCount`,`repostCount`,`isUserPost`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: SocialPostEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.postId)
        statement.bindText(3, entity.authorId)
        statement.bindText(4, entity.authorName)
        statement.bindText(5, entity.authorHandle)
        val _tmpAuthorAvatarUrl: String? = entity.authorAvatarUrl
        if (_tmpAuthorAvatarUrl == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpAuthorAvatarUrl)
        }
        statement.bindText(7, entity.content)
        val _tmpImageUrl: String? = entity.imageUrl
        if (_tmpImageUrl == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpImageUrl)
        }
        statement.bindLong(9, entity.timestamp)
        statement.bindLong(10, entity.likesCount.toLong())
        statement.bindLong(11, entity.repostCount.toLong())
        val _tmp: Int = if (entity.isUserPost) 1 else 0
        statement.bindLong(12, _tmp.toLong())
      }
    }
    this.__insertAdapterOfSocialCommentEntity_1 = object :
        EntityInsertAdapter<SocialCommentEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR IGNORE INTO `social_comments` (`id`,`commentId`,`postId`,`parentCommentId`,`authorId`,`authorName`,`authorHandle`,`authorAvatarUrl`,`content`,`timestamp`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: SocialCommentEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.commentId)
        statement.bindText(3, entity.postId)
        val _tmpParentCommentId: String? = entity.parentCommentId
        if (_tmpParentCommentId == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpParentCommentId)
        }
        statement.bindText(5, entity.authorId)
        statement.bindText(6, entity.authorName)
        statement.bindText(7, entity.authorHandle)
        val _tmpAuthorAvatarUrl: String? = entity.authorAvatarUrl
        if (_tmpAuthorAvatarUrl == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpAuthorAvatarUrl)
        }
        statement.bindText(9, entity.content)
        statement.bindLong(10, entity.timestamp)
      }
    }
    this.__insertAdapterOfSocialBotEntity_1 = object : EntityInsertAdapter<SocialBotEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR IGNORE INTO `social_bots` (`botId`,`name`,`handle`,`bio`,`avatarUrl`,`personaType`,`personality`,`lastPostTime`) VALUES (?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: SocialBotEntity) {
        statement.bindText(1, entity.botId)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.handle)
        statement.bindText(4, entity.bio)
        val _tmpAvatarUrl: String? = entity.avatarUrl
        if (_tmpAvatarUrl == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpAvatarUrl)
        }
        statement.bindText(6, entity.personaType)
        statement.bindText(7, entity.personality)
        statement.bindLong(8, entity.lastPostTime)
      }
    }
    this.__insertAdapterOfSocialFollowEntity = object : EntityInsertAdapter<SocialFollowEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `social_follows` (`id`,`botId`,`timestamp`) VALUES (nullif(?, 0),?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: SocialFollowEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.botId)
        statement.bindLong(3, entity.timestamp)
      }
    }
  }

  public override suspend fun insertPost(post: SocialPostEntity): Unit = performSuspending(__db,
      false, true) { _connection ->
    __insertAdapterOfSocialPostEntity.insert(_connection, post)
  }

  public override suspend fun insertBot(bot: SocialBotEntity): Unit = performSuspending(__db, false,
      true) { _connection ->
    __insertAdapterOfSocialBotEntity.insert(_connection, bot)
  }

  public override suspend fun insertComment(comment: SocialCommentEntity): Unit =
      performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfSocialCommentEntity.insert(_connection, comment)
  }

  public override suspend fun insertInteraction(interaction: SocialInteractionEntity): Unit =
      performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfSocialInteractionEntity.insert(_connection, interaction)
  }

  public override suspend fun insertMessage(msg: SocialMessageEntity): Unit =
      performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfSocialMessageEntity.insert(_connection, msg)
  }

  public override suspend fun insertPostIfNew(post: SocialPostEntity): Unit =
      performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfSocialPostEntity_1.insert(_connection, post)
  }

  public override suspend fun insertCommentIfNew(comment: SocialCommentEntity): Unit =
      performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfSocialCommentEntity_1.insert(_connection, comment)
  }

  public override suspend fun insertBotIfNew(bot: SocialBotEntity): Unit = performSuspending(__db,
      false, true) { _connection ->
    __insertAdapterOfSocialBotEntity_1.insert(_connection, bot)
  }

  public override suspend fun follow(follow: SocialFollowEntity): Unit = performSuspending(__db,
      false, true) { _connection ->
    __insertAdapterOfSocialFollowEntity.insert(_connection, follow)
  }

  public override suspend fun getAllPosts(): List<SocialPostEntity> {
    val _sql: String = "SELECT * FROM social_posts ORDER BY timestamp DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPostId: Int = getColumnIndexOrThrow(_stmt, "postId")
        val _columnIndexOfAuthorId: Int = getColumnIndexOrThrow(_stmt, "authorId")
        val _columnIndexOfAuthorName: Int = getColumnIndexOrThrow(_stmt, "authorName")
        val _columnIndexOfAuthorHandle: Int = getColumnIndexOrThrow(_stmt, "authorHandle")
        val _columnIndexOfAuthorAvatarUrl: Int = getColumnIndexOrThrow(_stmt, "authorAvatarUrl")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfImageUrl: Int = getColumnIndexOrThrow(_stmt, "imageUrl")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfLikesCount: Int = getColumnIndexOrThrow(_stmt, "likesCount")
        val _columnIndexOfRepostCount: Int = getColumnIndexOrThrow(_stmt, "repostCount")
        val _columnIndexOfIsUserPost: Int = getColumnIndexOrThrow(_stmt, "isUserPost")
        val _result: MutableList<SocialPostEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: SocialPostEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPostId: String
          _tmpPostId = _stmt.getText(_columnIndexOfPostId)
          val _tmpAuthorId: String
          _tmpAuthorId = _stmt.getText(_columnIndexOfAuthorId)
          val _tmpAuthorName: String
          _tmpAuthorName = _stmt.getText(_columnIndexOfAuthorName)
          val _tmpAuthorHandle: String
          _tmpAuthorHandle = _stmt.getText(_columnIndexOfAuthorHandle)
          val _tmpAuthorAvatarUrl: String?
          if (_stmt.isNull(_columnIndexOfAuthorAvatarUrl)) {
            _tmpAuthorAvatarUrl = null
          } else {
            _tmpAuthorAvatarUrl = _stmt.getText(_columnIndexOfAuthorAvatarUrl)
          }
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpImageUrl: String?
          if (_stmt.isNull(_columnIndexOfImageUrl)) {
            _tmpImageUrl = null
          } else {
            _tmpImageUrl = _stmt.getText(_columnIndexOfImageUrl)
          }
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpLikesCount: Int
          _tmpLikesCount = _stmt.getLong(_columnIndexOfLikesCount).toInt()
          val _tmpRepostCount: Int
          _tmpRepostCount = _stmt.getLong(_columnIndexOfRepostCount).toInt()
          val _tmpIsUserPost: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsUserPost).toInt()
          _tmpIsUserPost = _tmp != 0
          _item =
              SocialPostEntity(_tmpId,_tmpPostId,_tmpAuthorId,_tmpAuthorName,_tmpAuthorHandle,_tmpAuthorAvatarUrl,_tmpContent,_tmpImageUrl,_tmpTimestamp,_tmpLikesCount,_tmpRepostCount,_tmpIsUserPost)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getPostsByAuthor(botId: String): List<SocialPostEntity> {
    val _sql: String = "SELECT * FROM social_posts WHERE authorId = ? ORDER BY timestamp DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, botId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPostId: Int = getColumnIndexOrThrow(_stmt, "postId")
        val _columnIndexOfAuthorId: Int = getColumnIndexOrThrow(_stmt, "authorId")
        val _columnIndexOfAuthorName: Int = getColumnIndexOrThrow(_stmt, "authorName")
        val _columnIndexOfAuthorHandle: Int = getColumnIndexOrThrow(_stmt, "authorHandle")
        val _columnIndexOfAuthorAvatarUrl: Int = getColumnIndexOrThrow(_stmt, "authorAvatarUrl")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfImageUrl: Int = getColumnIndexOrThrow(_stmt, "imageUrl")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfLikesCount: Int = getColumnIndexOrThrow(_stmt, "likesCount")
        val _columnIndexOfRepostCount: Int = getColumnIndexOrThrow(_stmt, "repostCount")
        val _columnIndexOfIsUserPost: Int = getColumnIndexOrThrow(_stmt, "isUserPost")
        val _result: MutableList<SocialPostEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: SocialPostEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPostId: String
          _tmpPostId = _stmt.getText(_columnIndexOfPostId)
          val _tmpAuthorId: String
          _tmpAuthorId = _stmt.getText(_columnIndexOfAuthorId)
          val _tmpAuthorName: String
          _tmpAuthorName = _stmt.getText(_columnIndexOfAuthorName)
          val _tmpAuthorHandle: String
          _tmpAuthorHandle = _stmt.getText(_columnIndexOfAuthorHandle)
          val _tmpAuthorAvatarUrl: String?
          if (_stmt.isNull(_columnIndexOfAuthorAvatarUrl)) {
            _tmpAuthorAvatarUrl = null
          } else {
            _tmpAuthorAvatarUrl = _stmt.getText(_columnIndexOfAuthorAvatarUrl)
          }
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpImageUrl: String?
          if (_stmt.isNull(_columnIndexOfImageUrl)) {
            _tmpImageUrl = null
          } else {
            _tmpImageUrl = _stmt.getText(_columnIndexOfImageUrl)
          }
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpLikesCount: Int
          _tmpLikesCount = _stmt.getLong(_columnIndexOfLikesCount).toInt()
          val _tmpRepostCount: Int
          _tmpRepostCount = _stmt.getLong(_columnIndexOfRepostCount).toInt()
          val _tmpIsUserPost: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsUserPost).toInt()
          _tmpIsUserPost = _tmp != 0
          _item =
              SocialPostEntity(_tmpId,_tmpPostId,_tmpAuthorId,_tmpAuthorName,_tmpAuthorHandle,_tmpAuthorAvatarUrl,_tmpContent,_tmpImageUrl,_tmpTimestamp,_tmpLikesCount,_tmpRepostCount,_tmpIsUserPost)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getPostById(postId: String): SocialPostEntity? {
    val _sql: String = "SELECT * FROM social_posts WHERE postId = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, postId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPostId: Int = getColumnIndexOrThrow(_stmt, "postId")
        val _columnIndexOfAuthorId: Int = getColumnIndexOrThrow(_stmt, "authorId")
        val _columnIndexOfAuthorName: Int = getColumnIndexOrThrow(_stmt, "authorName")
        val _columnIndexOfAuthorHandle: Int = getColumnIndexOrThrow(_stmt, "authorHandle")
        val _columnIndexOfAuthorAvatarUrl: Int = getColumnIndexOrThrow(_stmt, "authorAvatarUrl")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfImageUrl: Int = getColumnIndexOrThrow(_stmt, "imageUrl")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfLikesCount: Int = getColumnIndexOrThrow(_stmt, "likesCount")
        val _columnIndexOfRepostCount: Int = getColumnIndexOrThrow(_stmt, "repostCount")
        val _columnIndexOfIsUserPost: Int = getColumnIndexOrThrow(_stmt, "isUserPost")
        val _result: SocialPostEntity?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPostId: String
          _tmpPostId = _stmt.getText(_columnIndexOfPostId)
          val _tmpAuthorId: String
          _tmpAuthorId = _stmt.getText(_columnIndexOfAuthorId)
          val _tmpAuthorName: String
          _tmpAuthorName = _stmt.getText(_columnIndexOfAuthorName)
          val _tmpAuthorHandle: String
          _tmpAuthorHandle = _stmt.getText(_columnIndexOfAuthorHandle)
          val _tmpAuthorAvatarUrl: String?
          if (_stmt.isNull(_columnIndexOfAuthorAvatarUrl)) {
            _tmpAuthorAvatarUrl = null
          } else {
            _tmpAuthorAvatarUrl = _stmt.getText(_columnIndexOfAuthorAvatarUrl)
          }
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpImageUrl: String?
          if (_stmt.isNull(_columnIndexOfImageUrl)) {
            _tmpImageUrl = null
          } else {
            _tmpImageUrl = _stmt.getText(_columnIndexOfImageUrl)
          }
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpLikesCount: Int
          _tmpLikesCount = _stmt.getLong(_columnIndexOfLikesCount).toInt()
          val _tmpRepostCount: Int
          _tmpRepostCount = _stmt.getLong(_columnIndexOfRepostCount).toInt()
          val _tmpIsUserPost: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsUserPost).toInt()
          _tmpIsUserPost = _tmp != 0
          _result =
              SocialPostEntity(_tmpId,_tmpPostId,_tmpAuthorId,_tmpAuthorName,_tmpAuthorHandle,_tmpAuthorAvatarUrl,_tmpContent,_tmpImageUrl,_tmpTimestamp,_tmpLikesCount,_tmpRepostCount,_tmpIsUserPost)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllBots(): List<SocialBotEntity> {
    val _sql: String = "SELECT * FROM social_bots"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfBotId: Int = getColumnIndexOrThrow(_stmt, "botId")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfHandle: Int = getColumnIndexOrThrow(_stmt, "handle")
        val _columnIndexOfBio: Int = getColumnIndexOrThrow(_stmt, "bio")
        val _columnIndexOfAvatarUrl: Int = getColumnIndexOrThrow(_stmt, "avatarUrl")
        val _columnIndexOfPersonaType: Int = getColumnIndexOrThrow(_stmt, "personaType")
        val _columnIndexOfPersonality: Int = getColumnIndexOrThrow(_stmt, "personality")
        val _columnIndexOfLastPostTime: Int = getColumnIndexOrThrow(_stmt, "lastPostTime")
        val _result: MutableList<SocialBotEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: SocialBotEntity
          val _tmpBotId: String
          _tmpBotId = _stmt.getText(_columnIndexOfBotId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpHandle: String
          _tmpHandle = _stmt.getText(_columnIndexOfHandle)
          val _tmpBio: String
          _tmpBio = _stmt.getText(_columnIndexOfBio)
          val _tmpAvatarUrl: String?
          if (_stmt.isNull(_columnIndexOfAvatarUrl)) {
            _tmpAvatarUrl = null
          } else {
            _tmpAvatarUrl = _stmt.getText(_columnIndexOfAvatarUrl)
          }
          val _tmpPersonaType: String
          _tmpPersonaType = _stmt.getText(_columnIndexOfPersonaType)
          val _tmpPersonality: String
          _tmpPersonality = _stmt.getText(_columnIndexOfPersonality)
          val _tmpLastPostTime: Long
          _tmpLastPostTime = _stmt.getLong(_columnIndexOfLastPostTime)
          _item =
              SocialBotEntity(_tmpBotId,_tmpName,_tmpHandle,_tmpBio,_tmpAvatarUrl,_tmpPersonaType,_tmpPersonality,_tmpLastPostTime)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getBot(id: String): SocialBotEntity? {
    val _sql: String = "SELECT * FROM social_bots WHERE botId = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        val _columnIndexOfBotId: Int = getColumnIndexOrThrow(_stmt, "botId")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfHandle: Int = getColumnIndexOrThrow(_stmt, "handle")
        val _columnIndexOfBio: Int = getColumnIndexOrThrow(_stmt, "bio")
        val _columnIndexOfAvatarUrl: Int = getColumnIndexOrThrow(_stmt, "avatarUrl")
        val _columnIndexOfPersonaType: Int = getColumnIndexOrThrow(_stmt, "personaType")
        val _columnIndexOfPersonality: Int = getColumnIndexOrThrow(_stmt, "personality")
        val _columnIndexOfLastPostTime: Int = getColumnIndexOrThrow(_stmt, "lastPostTime")
        val _result: SocialBotEntity?
        if (_stmt.step()) {
          val _tmpBotId: String
          _tmpBotId = _stmt.getText(_columnIndexOfBotId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpHandle: String
          _tmpHandle = _stmt.getText(_columnIndexOfHandle)
          val _tmpBio: String
          _tmpBio = _stmt.getText(_columnIndexOfBio)
          val _tmpAvatarUrl: String?
          if (_stmt.isNull(_columnIndexOfAvatarUrl)) {
            _tmpAvatarUrl = null
          } else {
            _tmpAvatarUrl = _stmt.getText(_columnIndexOfAvatarUrl)
          }
          val _tmpPersonaType: String
          _tmpPersonaType = _stmt.getText(_columnIndexOfPersonaType)
          val _tmpPersonality: String
          _tmpPersonality = _stmt.getText(_columnIndexOfPersonality)
          val _tmpLastPostTime: Long
          _tmpLastPostTime = _stmt.getLong(_columnIndexOfLastPostTime)
          _result =
              SocialBotEntity(_tmpBotId,_tmpName,_tmpHandle,_tmpBio,_tmpAvatarUrl,_tmpPersonaType,_tmpPersonality,_tmpLastPostTime)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getCommentsForPost(postId: String): List<SocialCommentEntity> {
    val _sql: String =
        "SELECT * FROM social_comments WHERE postId = ? AND parentCommentId IS NULL ORDER BY timestamp ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, postId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfCommentId: Int = getColumnIndexOrThrow(_stmt, "commentId")
        val _columnIndexOfPostId: Int = getColumnIndexOrThrow(_stmt, "postId")
        val _columnIndexOfParentCommentId: Int = getColumnIndexOrThrow(_stmt, "parentCommentId")
        val _columnIndexOfAuthorId: Int = getColumnIndexOrThrow(_stmt, "authorId")
        val _columnIndexOfAuthorName: Int = getColumnIndexOrThrow(_stmt, "authorName")
        val _columnIndexOfAuthorHandle: Int = getColumnIndexOrThrow(_stmt, "authorHandle")
        val _columnIndexOfAuthorAvatarUrl: Int = getColumnIndexOrThrow(_stmt, "authorAvatarUrl")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<SocialCommentEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: SocialCommentEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpCommentId: String
          _tmpCommentId = _stmt.getText(_columnIndexOfCommentId)
          val _tmpPostId: String
          _tmpPostId = _stmt.getText(_columnIndexOfPostId)
          val _tmpParentCommentId: String?
          if (_stmt.isNull(_columnIndexOfParentCommentId)) {
            _tmpParentCommentId = null
          } else {
            _tmpParentCommentId = _stmt.getText(_columnIndexOfParentCommentId)
          }
          val _tmpAuthorId: String
          _tmpAuthorId = _stmt.getText(_columnIndexOfAuthorId)
          val _tmpAuthorName: String
          _tmpAuthorName = _stmt.getText(_columnIndexOfAuthorName)
          val _tmpAuthorHandle: String
          _tmpAuthorHandle = _stmt.getText(_columnIndexOfAuthorHandle)
          val _tmpAuthorAvatarUrl: String?
          if (_stmt.isNull(_columnIndexOfAuthorAvatarUrl)) {
            _tmpAuthorAvatarUrl = null
          } else {
            _tmpAuthorAvatarUrl = _stmt.getText(_columnIndexOfAuthorAvatarUrl)
          }
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item =
              SocialCommentEntity(_tmpId,_tmpCommentId,_tmpPostId,_tmpParentCommentId,_tmpAuthorId,_tmpAuthorName,_tmpAuthorHandle,_tmpAuthorAvatarUrl,_tmpContent,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getReplies(parentCommentId: String): List<SocialCommentEntity> {
    val _sql: String =
        "SELECT * FROM social_comments WHERE parentCommentId = ? ORDER BY timestamp ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, parentCommentId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfCommentId: Int = getColumnIndexOrThrow(_stmt, "commentId")
        val _columnIndexOfPostId: Int = getColumnIndexOrThrow(_stmt, "postId")
        val _columnIndexOfParentCommentId: Int = getColumnIndexOrThrow(_stmt, "parentCommentId")
        val _columnIndexOfAuthorId: Int = getColumnIndexOrThrow(_stmt, "authorId")
        val _columnIndexOfAuthorName: Int = getColumnIndexOrThrow(_stmt, "authorName")
        val _columnIndexOfAuthorHandle: Int = getColumnIndexOrThrow(_stmt, "authorHandle")
        val _columnIndexOfAuthorAvatarUrl: Int = getColumnIndexOrThrow(_stmt, "authorAvatarUrl")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<SocialCommentEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: SocialCommentEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpCommentId: String
          _tmpCommentId = _stmt.getText(_columnIndexOfCommentId)
          val _tmpPostId: String
          _tmpPostId = _stmt.getText(_columnIndexOfPostId)
          val _tmpParentCommentId: String?
          if (_stmt.isNull(_columnIndexOfParentCommentId)) {
            _tmpParentCommentId = null
          } else {
            _tmpParentCommentId = _stmt.getText(_columnIndexOfParentCommentId)
          }
          val _tmpAuthorId: String
          _tmpAuthorId = _stmt.getText(_columnIndexOfAuthorId)
          val _tmpAuthorName: String
          _tmpAuthorName = _stmt.getText(_columnIndexOfAuthorName)
          val _tmpAuthorHandle: String
          _tmpAuthorHandle = _stmt.getText(_columnIndexOfAuthorHandle)
          val _tmpAuthorAvatarUrl: String?
          if (_stmt.isNull(_columnIndexOfAuthorAvatarUrl)) {
            _tmpAuthorAvatarUrl = null
          } else {
            _tmpAuthorAvatarUrl = _stmt.getText(_columnIndexOfAuthorAvatarUrl)
          }
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item =
              SocialCommentEntity(_tmpId,_tmpCommentId,_tmpPostId,_tmpParentCommentId,_tmpAuthorId,_tmpAuthorName,_tmpAuthorHandle,_tmpAuthorAvatarUrl,_tmpContent,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getInteractions(postId: String, type: String):
      List<SocialInteractionEntity> {
    val _sql: String = "SELECT * FROM social_interactions WHERE postId = ? AND type = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, postId)
        _argIndex = 2
        _stmt.bindText(_argIndex, type)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPostId: Int = getColumnIndexOrThrow(_stmt, "postId")
        val _columnIndexOfActorId: Int = getColumnIndexOrThrow(_stmt, "actorId")
        val _columnIndexOfActorName: Int = getColumnIndexOrThrow(_stmt, "actorName")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<SocialInteractionEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: SocialInteractionEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPostId: String
          _tmpPostId = _stmt.getText(_columnIndexOfPostId)
          val _tmpActorId: String
          _tmpActorId = _stmt.getText(_columnIndexOfActorId)
          val _tmpActorName: String
          _tmpActorName = _stmt.getText(_columnIndexOfActorName)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item =
              SocialInteractionEntity(_tmpId,_tmpPostId,_tmpActorId,_tmpActorName,_tmpType,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getMessagesForChat(chatId: String): List<SocialMessageEntity> {
    val _sql: String = "SELECT * FROM social_messages WHERE chatId = ? ORDER BY timestamp ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, chatId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfChatId: Int = getColumnIndexOrThrow(_stmt, "chatId")
        val _columnIndexOfSenderId: Int = getColumnIndexOrThrow(_stmt, "senderId")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<SocialMessageEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: SocialMessageEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpChatId: String
          _tmpChatId = _stmt.getText(_columnIndexOfChatId)
          val _tmpSenderId: String
          _tmpSenderId = _stmt.getText(_columnIndexOfSenderId)
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item = SocialMessageEntity(_tmpId,_tmpChatId,_tmpSenderId,_tmpContent,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getRecentChats(): List<SocialMessageEntity> {
    val _sql: String = "SELECT * FROM social_messages GROUP BY chatId ORDER BY timestamp DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfChatId: Int = getColumnIndexOrThrow(_stmt, "chatId")
        val _columnIndexOfSenderId: Int = getColumnIndexOrThrow(_stmt, "senderId")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<SocialMessageEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: SocialMessageEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpChatId: String
          _tmpChatId = _stmt.getText(_columnIndexOfChatId)
          val _tmpSenderId: String
          _tmpSenderId = _stmt.getText(_columnIndexOfSenderId)
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item = SocialMessageEntity(_tmpId,_tmpChatId,_tmpSenderId,_tmpContent,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun isFollowing(botId: String): Boolean {
    val _sql: String = "SELECT COUNT(*) FROM social_follows WHERE botId = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, botId)
        val _result: Boolean
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp != 0
        } else {
          _result = false
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun recentPosts(limit: Int): List<SocialPostEntity> {
    val _sql: String = "SELECT * FROM social_posts ORDER BY timestamp DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPostId: Int = getColumnIndexOrThrow(_stmt, "postId")
        val _columnIndexOfAuthorId: Int = getColumnIndexOrThrow(_stmt, "authorId")
        val _columnIndexOfAuthorName: Int = getColumnIndexOrThrow(_stmt, "authorName")
        val _columnIndexOfAuthorHandle: Int = getColumnIndexOrThrow(_stmt, "authorHandle")
        val _columnIndexOfAuthorAvatarUrl: Int = getColumnIndexOrThrow(_stmt, "authorAvatarUrl")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfImageUrl: Int = getColumnIndexOrThrow(_stmt, "imageUrl")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfLikesCount: Int = getColumnIndexOrThrow(_stmt, "likesCount")
        val _columnIndexOfRepostCount: Int = getColumnIndexOrThrow(_stmt, "repostCount")
        val _columnIndexOfIsUserPost: Int = getColumnIndexOrThrow(_stmt, "isUserPost")
        val _result: MutableList<SocialPostEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: SocialPostEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpPostId: String
          _tmpPostId = _stmt.getText(_columnIndexOfPostId)
          val _tmpAuthorId: String
          _tmpAuthorId = _stmt.getText(_columnIndexOfAuthorId)
          val _tmpAuthorName: String
          _tmpAuthorName = _stmt.getText(_columnIndexOfAuthorName)
          val _tmpAuthorHandle: String
          _tmpAuthorHandle = _stmt.getText(_columnIndexOfAuthorHandle)
          val _tmpAuthorAvatarUrl: String?
          if (_stmt.isNull(_columnIndexOfAuthorAvatarUrl)) {
            _tmpAuthorAvatarUrl = null
          } else {
            _tmpAuthorAvatarUrl = _stmt.getText(_columnIndexOfAuthorAvatarUrl)
          }
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpImageUrl: String?
          if (_stmt.isNull(_columnIndexOfImageUrl)) {
            _tmpImageUrl = null
          } else {
            _tmpImageUrl = _stmt.getText(_columnIndexOfImageUrl)
          }
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpLikesCount: Int
          _tmpLikesCount = _stmt.getLong(_columnIndexOfLikesCount).toInt()
          val _tmpRepostCount: Int
          _tmpRepostCount = _stmt.getLong(_columnIndexOfRepostCount).toInt()
          val _tmpIsUserPost: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsUserPost).toInt()
          _tmpIsUserPost = _tmp != 0
          _item =
              SocialPostEntity(_tmpId,_tmpPostId,_tmpAuthorId,_tmpAuthorName,_tmpAuthorHandle,_tmpAuthorAvatarUrl,_tmpContent,_tmpImageUrl,_tmpTimestamp,_tmpLikesCount,_tmpRepostCount,_tmpIsUserPost)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun recentComments(limit: Int): List<SocialCommentEntity> {
    val _sql: String = "SELECT * FROM social_comments ORDER BY timestamp DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfCommentId: Int = getColumnIndexOrThrow(_stmt, "commentId")
        val _columnIndexOfPostId: Int = getColumnIndexOrThrow(_stmt, "postId")
        val _columnIndexOfParentCommentId: Int = getColumnIndexOrThrow(_stmt, "parentCommentId")
        val _columnIndexOfAuthorId: Int = getColumnIndexOrThrow(_stmt, "authorId")
        val _columnIndexOfAuthorName: Int = getColumnIndexOrThrow(_stmt, "authorName")
        val _columnIndexOfAuthorHandle: Int = getColumnIndexOrThrow(_stmt, "authorHandle")
        val _columnIndexOfAuthorAvatarUrl: Int = getColumnIndexOrThrow(_stmt, "authorAvatarUrl")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<SocialCommentEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: SocialCommentEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpCommentId: String
          _tmpCommentId = _stmt.getText(_columnIndexOfCommentId)
          val _tmpPostId: String
          _tmpPostId = _stmt.getText(_columnIndexOfPostId)
          val _tmpParentCommentId: String?
          if (_stmt.isNull(_columnIndexOfParentCommentId)) {
            _tmpParentCommentId = null
          } else {
            _tmpParentCommentId = _stmt.getText(_columnIndexOfParentCommentId)
          }
          val _tmpAuthorId: String
          _tmpAuthorId = _stmt.getText(_columnIndexOfAuthorId)
          val _tmpAuthorName: String
          _tmpAuthorName = _stmt.getText(_columnIndexOfAuthorName)
          val _tmpAuthorHandle: String
          _tmpAuthorHandle = _stmt.getText(_columnIndexOfAuthorHandle)
          val _tmpAuthorAvatarUrl: String?
          if (_stmt.isNull(_columnIndexOfAuthorAvatarUrl)) {
            _tmpAuthorAvatarUrl = null
          } else {
            _tmpAuthorAvatarUrl = _stmt.getText(_columnIndexOfAuthorAvatarUrl)
          }
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item =
              SocialCommentEntity(_tmpId,_tmpCommentId,_tmpPostId,_tmpParentCommentId,_tmpAuthorId,_tmpAuthorName,_tmpAuthorHandle,_tmpAuthorAvatarUrl,_tmpContent,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun countPosts(): Int {
    val _sql: String = "SELECT COUNT(*) FROM social_posts"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getCommentById(commentId: String): SocialCommentEntity? {
    val _sql: String = "SELECT * FROM social_comments WHERE commentId = ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, commentId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfCommentId: Int = getColumnIndexOrThrow(_stmt, "commentId")
        val _columnIndexOfPostId: Int = getColumnIndexOrThrow(_stmt, "postId")
        val _columnIndexOfParentCommentId: Int = getColumnIndexOrThrow(_stmt, "parentCommentId")
        val _columnIndexOfAuthorId: Int = getColumnIndexOrThrow(_stmt, "authorId")
        val _columnIndexOfAuthorName: Int = getColumnIndexOrThrow(_stmt, "authorName")
        val _columnIndexOfAuthorHandle: Int = getColumnIndexOrThrow(_stmt, "authorHandle")
        val _columnIndexOfAuthorAvatarUrl: Int = getColumnIndexOrThrow(_stmt, "authorAvatarUrl")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: SocialCommentEntity?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpCommentId: String
          _tmpCommentId = _stmt.getText(_columnIndexOfCommentId)
          val _tmpPostId: String
          _tmpPostId = _stmt.getText(_columnIndexOfPostId)
          val _tmpParentCommentId: String?
          if (_stmt.isNull(_columnIndexOfParentCommentId)) {
            _tmpParentCommentId = null
          } else {
            _tmpParentCommentId = _stmt.getText(_columnIndexOfParentCommentId)
          }
          val _tmpAuthorId: String
          _tmpAuthorId = _stmt.getText(_columnIndexOfAuthorId)
          val _tmpAuthorName: String
          _tmpAuthorName = _stmt.getText(_columnIndexOfAuthorName)
          val _tmpAuthorHandle: String
          _tmpAuthorHandle = _stmt.getText(_columnIndexOfAuthorHandle)
          val _tmpAuthorAvatarUrl: String?
          if (_stmt.isNull(_columnIndexOfAuthorAvatarUrl)) {
            _tmpAuthorAvatarUrl = null
          } else {
            _tmpAuthorAvatarUrl = _stmt.getText(_columnIndexOfAuthorAvatarUrl)
          }
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _result =
              SocialCommentEntity(_tmpId,_tmpCommentId,_tmpPostId,_tmpParentCommentId,_tmpAuthorId,_tmpAuthorName,_tmpAuthorHandle,_tmpAuthorAvatarUrl,_tmpContent,_tmpTimestamp)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun unfollow(botId: String) {
    val _sql: String = "DELETE FROM social_follows WHERE botId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, botId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
