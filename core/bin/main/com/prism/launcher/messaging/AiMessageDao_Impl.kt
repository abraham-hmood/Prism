package com.prism.launcher.messaging

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
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
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AiMessageDao_Impl(
  __db: RoomDatabase,
) : AiMessageDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfAiMessageEntity: EntityInsertAdapter<AiMessageEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfAiMessageEntity = object : EntityInsertAdapter<AiMessageEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR ABORT INTO `ai_messages` (`id`,`text`,`isSent`,`timestamp`,`attachmentUri`,`attachmentType`) VALUES (nullif(?, 0),?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: AiMessageEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.text)
        val _tmp: Int = if (entity.isSent) 1 else 0
        statement.bindLong(3, _tmp.toLong())
        statement.bindLong(4, entity.timestamp)
        val _tmpAttachmentUri: String? = entity.attachmentUri
        if (_tmpAttachmentUri == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpAttachmentUri)
        }
        val _tmpAttachmentType: String? = entity.attachmentType
        if (_tmpAttachmentType == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpAttachmentType)
        }
      }
    }
  }

  public override suspend fun insert(message: AiMessageEntity): Unit = performSuspending(__db,
      false, true) { _connection ->
    __insertAdapterOfAiMessageEntity.insert(_connection, message)
  }

  public override fun getAllMessages(): Flow<List<AiMessageEntity>> {
    val _sql: String = "SELECT * FROM ai_messages ORDER BY timestamp ASC"
    return createFlow(__db, false, arrayOf("ai_messages")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfText: Int = getColumnIndexOrThrow(_stmt, "text")
        val _columnIndexOfIsSent: Int = getColumnIndexOrThrow(_stmt, "isSent")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfAttachmentUri: Int = getColumnIndexOrThrow(_stmt, "attachmentUri")
        val _columnIndexOfAttachmentType: Int = getColumnIndexOrThrow(_stmt, "attachmentType")
        val _result: MutableList<AiMessageEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: AiMessageEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpText: String
          _tmpText = _stmt.getText(_columnIndexOfText)
          val _tmpIsSent: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsSent).toInt()
          _tmpIsSent = _tmp != 0
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpAttachmentUri: String?
          if (_stmt.isNull(_columnIndexOfAttachmentUri)) {
            _tmpAttachmentUri = null
          } else {
            _tmpAttachmentUri = _stmt.getText(_columnIndexOfAttachmentUri)
          }
          val _tmpAttachmentType: String?
          if (_stmt.isNull(_columnIndexOfAttachmentType)) {
            _tmpAttachmentType = null
          } else {
            _tmpAttachmentType = _stmt.getText(_columnIndexOfAttachmentType)
          }
          _item =
              AiMessageEntity(_tmpId,_tmpText,_tmpIsSent,_tmpTimestamp,_tmpAttachmentUri,_tmpAttachmentType)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun searchMessages(query: String): List<AiMessageEntity> {
    val _sql: String =
        "SELECT * FROM ai_messages WHERE text LIKE '%' || ? || '%' ORDER BY timestamp DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, query)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfText: Int = getColumnIndexOrThrow(_stmt, "text")
        val _columnIndexOfIsSent: Int = getColumnIndexOrThrow(_stmt, "isSent")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfAttachmentUri: Int = getColumnIndexOrThrow(_stmt, "attachmentUri")
        val _columnIndexOfAttachmentType: Int = getColumnIndexOrThrow(_stmt, "attachmentType")
        val _result: MutableList<AiMessageEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: AiMessageEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpText: String
          _tmpText = _stmt.getText(_columnIndexOfText)
          val _tmpIsSent: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsSent).toInt()
          _tmpIsSent = _tmp != 0
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpAttachmentUri: String?
          if (_stmt.isNull(_columnIndexOfAttachmentUri)) {
            _tmpAttachmentUri = null
          } else {
            _tmpAttachmentUri = _stmt.getText(_columnIndexOfAttachmentUri)
          }
          val _tmpAttachmentType: String?
          if (_stmt.isNull(_columnIndexOfAttachmentType)) {
            _tmpAttachmentType = null
          } else {
            _tmpAttachmentType = _stmt.getText(_columnIndexOfAttachmentType)
          }
          _item =
              AiMessageEntity(_tmpId,_tmpText,_tmpIsSent,_tmpTimestamp,_tmpAttachmentUri,_tmpAttachmentType)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteAll() {
    val _sql: String = "DELETE FROM ai_messages"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
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
