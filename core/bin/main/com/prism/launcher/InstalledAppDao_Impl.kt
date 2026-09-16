package com.prism.launcher

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Int
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
public class InstalledAppDao_Impl(
  __db: RoomDatabase,
) : InstalledAppDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfInstalledAppEntity: EntityInsertAdapter<InstalledAppEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfInstalledAppEntity = object : EntityInsertAdapter<InstalledAppEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `installed_apps` (`packageName`,`activityClass`) VALUES (?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: InstalledAppEntity) {
        statement.bindText(1, entity.packageName)
        statement.bindText(2, entity.activityClass)
      }
    }
  }

  public override suspend fun insertAll(apps: List<InstalledAppEntity>): Unit =
      performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfInstalledAppEntity.insert(_connection, apps)
  }

  public override suspend fun getAll(): List<InstalledAppEntity> {
    val _sql: String = "SELECT * FROM installed_apps ORDER BY packageName ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfActivityClass: Int = getColumnIndexOrThrow(_stmt, "activityClass")
        val _result: MutableList<InstalledAppEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: InstalledAppEntity
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpActivityClass: String
          _tmpActivityClass = _stmt.getText(_columnIndexOfActivityClass)
          _item = InstalledAppEntity(_tmpPackageName,_tmpActivityClass)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observeAll(): Flow<List<InstalledAppEntity>> {
    val _sql: String = "SELECT * FROM installed_apps ORDER BY packageName ASC"
    return createFlow(__db, false, arrayOf("installed_apps")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfPackageName: Int = getColumnIndexOrThrow(_stmt, "packageName")
        val _columnIndexOfActivityClass: Int = getColumnIndexOrThrow(_stmt, "activityClass")
        val _result: MutableList<InstalledAppEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: InstalledAppEntity
          val _tmpPackageName: String
          _tmpPackageName = _stmt.getText(_columnIndexOfPackageName)
          val _tmpActivityClass: String
          _tmpActivityClass = _stmt.getText(_columnIndexOfActivityClass)
          _item = InstalledAppEntity(_tmpPackageName,_tmpActivityClass)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun count(): Int {
    val _sql: String = "SELECT COUNT(*) FROM installed_apps"
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

  public override suspend fun deleteByPackage(pkg: String) {
    val _sql: String = "DELETE FROM installed_apps WHERE packageName = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, pkg)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun clearAll() {
    val _sql: String = "DELETE FROM installed_apps"
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
