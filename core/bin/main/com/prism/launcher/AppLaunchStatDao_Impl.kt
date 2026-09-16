package com.prism.launcher

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.getTotalChangedRows
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AppLaunchStatDao_Impl(
  __db: RoomDatabase,
) : AppLaunchStatDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfAppLaunchStatEntity: EntityInsertAdapter<AppLaunchStatEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfAppLaunchStatEntity = object : EntityInsertAdapter<AppLaunchStatEntity>()
        {
      protected override fun createQuery(): String =
          "INSERT OR IGNORE INTO `app_launch_stats` (`componentName`,`hourOfDay`,`launchCount`) VALUES (?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: AppLaunchStatEntity) {
        statement.bindText(1, entity.componentName)
        statement.bindLong(2, entity.hourOfDay.toLong())
        statement.bindLong(3, entity.launchCount.toLong())
      }
    }
  }

  public override suspend fun insertOrIgnore(entity: AppLaunchStatEntity): Long =
      performSuspending(__db, false, true) { _connection ->
    val _result: Long = __insertAdapterOfAppLaunchStatEntity.insertAndReturnId(_connection, entity)
    _result
  }

  public override suspend fun getTopForHour(hourOfDay: Int, limit: Int): List<String> {
    val _sql: String = """
        |
        |        SELECT componentName 
        |        FROM app_launch_stats 
        |        WHERE hourOfDay = ? 
        |        ORDER BY launchCount DESC 
        |        LIMIT ?
        |    
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, hourOfDay.toLong())
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item: String
          _item = _stmt.getText(0)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getStatsForHour(hourOfDay: Int): List<AppLaunchStatEntity> {
    val _sql: String = "SELECT * FROM app_launch_stats WHERE hourOfDay = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, hourOfDay.toLong())
        val _columnIndexOfComponentName: Int = getColumnIndexOrThrow(_stmt, "componentName")
        val _columnIndexOfHourOfDay: Int = getColumnIndexOrThrow(_stmt, "hourOfDay")
        val _columnIndexOfLaunchCount: Int = getColumnIndexOrThrow(_stmt, "launchCount")
        val _result: MutableList<AppLaunchStatEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: AppLaunchStatEntity
          val _tmpComponentName: String
          _tmpComponentName = _stmt.getText(_columnIndexOfComponentName)
          val _tmpHourOfDay: Int
          _tmpHourOfDay = _stmt.getLong(_columnIndexOfHourOfDay).toInt()
          val _tmpLaunchCount: Int
          _tmpLaunchCount = _stmt.getLong(_columnIndexOfLaunchCount).toInt()
          _item = AppLaunchStatEntity(_tmpComponentName,_tmpHourOfDay,_tmpLaunchCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTopOverall(limit: Int): List<String> {
    val _sql: String = """
        |
        |        SELECT componentName 
        |        FROM app_launch_stats 
        |        ORDER BY launchCount DESC 
        |        LIMIT ?
        |    
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item: String
          _item = _stmt.getText(0)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun increment(componentName: String, hourOfDay: Int): Int {
    val _sql: String = """
        |
        |        UPDATE app_launch_stats 
        |        SET launchCount = launchCount + 1 
        |        WHERE componentName = ? AND hourOfDay = ?
        |    
        """.trimMargin()
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, componentName)
        _argIndex = 2
        _stmt.bindLong(_argIndex, hourOfDay.toLong())
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
