package com.prism.launcher.agentic

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AgenticDao_Impl(
  __db: RoomDatabase,
) : AgenticDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfAgenticToolEntity: EntityInsertAdapter<AgenticToolEntity>

  private val __insertAdapterOfAgenticSyntaxEntity: EntityInsertAdapter<AgenticSyntaxEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfAgenticToolEntity = object : EntityInsertAdapter<AgenticToolEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `agentic_tools` (`id`,`name`,`description`,`parametersJson`,`httpMethod`,`httpUrl`,`httpHeadersJson`,`httpBodyTemplate`,`enabled`) VALUES (?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: AgenticToolEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.description)
        statement.bindText(4, entity.parametersJson)
        statement.bindText(5, entity.httpMethod)
        statement.bindText(6, entity.httpUrl)
        statement.bindText(7, entity.httpHeadersJson)
        statement.bindText(8, entity.httpBodyTemplate)
        val _tmp: Int = if (entity.enabled) 1 else 0
        statement.bindLong(9, _tmp.toLong())
      }
    }
    this.__insertAdapterOfAgenticSyntaxEntity = object : EntityInsertAdapter<AgenticSyntaxEntity>()
        {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `agentic_syntaxes` (`id`,`name`,`systemPromptTemplate`,`toolFormatTemplate`,`callExtractionRegex`) VALUES (?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: AgenticSyntaxEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.systemPromptTemplate)
        statement.bindText(4, entity.toolFormatTemplate)
        statement.bindText(5, entity.callExtractionRegex)
      }
    }
  }

  public override suspend fun upsertTool(tool: AgenticToolEntity): Unit = performSuspending(__db,
      false, true) { _connection ->
    __insertAdapterOfAgenticToolEntity.insert(_connection, tool)
  }

  public override suspend fun upsertSyntax(syntax: AgenticSyntaxEntity): Unit =
      performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfAgenticSyntaxEntity.insert(_connection, syntax)
  }

  public override suspend fun getAllTools(): List<AgenticToolEntity> {
    val _sql: String = "SELECT * FROM agentic_tools ORDER BY name ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfParametersJson: Int = getColumnIndexOrThrow(_stmt, "parametersJson")
        val _columnIndexOfHttpMethod: Int = getColumnIndexOrThrow(_stmt, "httpMethod")
        val _columnIndexOfHttpUrl: Int = getColumnIndexOrThrow(_stmt, "httpUrl")
        val _columnIndexOfHttpHeadersJson: Int = getColumnIndexOrThrow(_stmt, "httpHeadersJson")
        val _columnIndexOfHttpBodyTemplate: Int = getColumnIndexOrThrow(_stmt, "httpBodyTemplate")
        val _columnIndexOfEnabled: Int = getColumnIndexOrThrow(_stmt, "enabled")
        val _result: MutableList<AgenticToolEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: AgenticToolEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpParametersJson: String
          _tmpParametersJson = _stmt.getText(_columnIndexOfParametersJson)
          val _tmpHttpMethod: String
          _tmpHttpMethod = _stmt.getText(_columnIndexOfHttpMethod)
          val _tmpHttpUrl: String
          _tmpHttpUrl = _stmt.getText(_columnIndexOfHttpUrl)
          val _tmpHttpHeadersJson: String
          _tmpHttpHeadersJson = _stmt.getText(_columnIndexOfHttpHeadersJson)
          val _tmpHttpBodyTemplate: String
          _tmpHttpBodyTemplate = _stmt.getText(_columnIndexOfHttpBodyTemplate)
          val _tmpEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfEnabled).toInt()
          _tmpEnabled = _tmp != 0
          _item =
              AgenticToolEntity(_tmpId,_tmpName,_tmpDescription,_tmpParametersJson,_tmpHttpMethod,_tmpHttpUrl,_tmpHttpHeadersJson,_tmpHttpBodyTemplate,_tmpEnabled)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getEnabledTools(): List<AgenticToolEntity> {
    val _sql: String = "SELECT * FROM agentic_tools WHERE enabled = 1 ORDER BY name ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfDescription: Int = getColumnIndexOrThrow(_stmt, "description")
        val _columnIndexOfParametersJson: Int = getColumnIndexOrThrow(_stmt, "parametersJson")
        val _columnIndexOfHttpMethod: Int = getColumnIndexOrThrow(_stmt, "httpMethod")
        val _columnIndexOfHttpUrl: Int = getColumnIndexOrThrow(_stmt, "httpUrl")
        val _columnIndexOfHttpHeadersJson: Int = getColumnIndexOrThrow(_stmt, "httpHeadersJson")
        val _columnIndexOfHttpBodyTemplate: Int = getColumnIndexOrThrow(_stmt, "httpBodyTemplate")
        val _columnIndexOfEnabled: Int = getColumnIndexOrThrow(_stmt, "enabled")
        val _result: MutableList<AgenticToolEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: AgenticToolEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpDescription: String
          _tmpDescription = _stmt.getText(_columnIndexOfDescription)
          val _tmpParametersJson: String
          _tmpParametersJson = _stmt.getText(_columnIndexOfParametersJson)
          val _tmpHttpMethod: String
          _tmpHttpMethod = _stmt.getText(_columnIndexOfHttpMethod)
          val _tmpHttpUrl: String
          _tmpHttpUrl = _stmt.getText(_columnIndexOfHttpUrl)
          val _tmpHttpHeadersJson: String
          _tmpHttpHeadersJson = _stmt.getText(_columnIndexOfHttpHeadersJson)
          val _tmpHttpBodyTemplate: String
          _tmpHttpBodyTemplate = _stmt.getText(_columnIndexOfHttpBodyTemplate)
          val _tmpEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfEnabled).toInt()
          _tmpEnabled = _tmp != 0
          _item =
              AgenticToolEntity(_tmpId,_tmpName,_tmpDescription,_tmpParametersJson,_tmpHttpMethod,_tmpHttpUrl,_tmpHttpHeadersJson,_tmpHttpBodyTemplate,_tmpEnabled)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllSyntaxes(): List<AgenticSyntaxEntity> {
    val _sql: String = "SELECT * FROM agentic_syntaxes ORDER BY name ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfSystemPromptTemplate: Int = getColumnIndexOrThrow(_stmt,
            "systemPromptTemplate")
        val _columnIndexOfToolFormatTemplate: Int = getColumnIndexOrThrow(_stmt,
            "toolFormatTemplate")
        val _columnIndexOfCallExtractionRegex: Int = getColumnIndexOrThrow(_stmt,
            "callExtractionRegex")
        val _result: MutableList<AgenticSyntaxEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: AgenticSyntaxEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpSystemPromptTemplate: String
          _tmpSystemPromptTemplate = _stmt.getText(_columnIndexOfSystemPromptTemplate)
          val _tmpToolFormatTemplate: String
          _tmpToolFormatTemplate = _stmt.getText(_columnIndexOfToolFormatTemplate)
          val _tmpCallExtractionRegex: String
          _tmpCallExtractionRegex = _stmt.getText(_columnIndexOfCallExtractionRegex)
          _item =
              AgenticSyntaxEntity(_tmpId,_tmpName,_tmpSystemPromptTemplate,_tmpToolFormatTemplate,_tmpCallExtractionRegex)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getSyntax(id: String): AgenticSyntaxEntity? {
    val _sql: String = "SELECT * FROM agentic_syntaxes WHERE id = ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfSystemPromptTemplate: Int = getColumnIndexOrThrow(_stmt,
            "systemPromptTemplate")
        val _columnIndexOfToolFormatTemplate: Int = getColumnIndexOrThrow(_stmt,
            "toolFormatTemplate")
        val _columnIndexOfCallExtractionRegex: Int = getColumnIndexOrThrow(_stmt,
            "callExtractionRegex")
        val _result: AgenticSyntaxEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpSystemPromptTemplate: String
          _tmpSystemPromptTemplate = _stmt.getText(_columnIndexOfSystemPromptTemplate)
          val _tmpToolFormatTemplate: String
          _tmpToolFormatTemplate = _stmt.getText(_columnIndexOfToolFormatTemplate)
          val _tmpCallExtractionRegex: String
          _tmpCallExtractionRegex = _stmt.getText(_columnIndexOfCallExtractionRegex)
          _result =
              AgenticSyntaxEntity(_tmpId,_tmpName,_tmpSystemPromptTemplate,_tmpToolFormatTemplate,_tmpCallExtractionRegex)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteTool(id: String) {
    val _sql: String = "DELETE FROM agentic_tools WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteSyntax(id: String) {
    val _sql: String = "DELETE FROM agentic_syntaxes WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
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
