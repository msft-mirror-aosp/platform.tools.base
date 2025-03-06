/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.test.inspectors.database

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.database.sqlite.SQLiteDatabase.OpenParams
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.RequiresApi
import androidx.lifecycle.viewModelScope
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.google.test.inspectors.Logger
import com.google.test.inspectors.SqlDelightDatabase
import com.google.test.inspectors.database.room.RoomDatabase
import com.google.test.inspectors.database.room.RoomUserEntity
import com.google.test.inspectors.ui.scafold.AppScaffoldViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val NATIVE_DATABASE = "native-database.db"
private const val NATIVE_DATABASE_VERSION = 1
// language=SQLite
private const val NATIVE_DATABASE_CREATE =
  """
  CREATE TABLE IF NOT EXISTS Users (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT
  )
  """

// language=SQLite
private const val INSERT_USER = "INSERT INTO Users(name) VALUES (?)"

private val SYSTEM_TABLES =
  listOf("sqlite_sequence", "room_master_table", "android_metadata").joinToString { "'$it'" }

private val QUERY_TABLES =
  """
    SELECT name FROM sqlite_master
      WHERE
        type = 'table' AND
        name NOT IN ($SYSTEM_TABLES)
  """

@HiltViewModel
internal class DatabaseViewModel @Inject constructor(private val application: Application) :
  AppScaffoldViewModel(), DatabaseActions {

  private val roomDatabase =
    Room.databaseBuilder(application, RoomDatabase::class.java, "room-database.db").build()

  private val bundledSQLiteDriver = BundledSQLiteDriver()

  private val roomBundledDatabase =
    Room.databaseBuilder(application, RoomDatabase::class.java, "room-bundled-database.db")
      .setDriver(bundledSQLiteDriver)
      .build()

  private val sqldelightDriver =
    AndroidSqliteDriver(SqlDelightDatabase.Schema, application, "sqldelight-database.db")
  private val sqlDelightDatabase = SqlDelightDatabase.invoke(sqldelightDriver)

  private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
    setSnack("Error: ${throwable.message}")
    Logger.error("Error: ${throwable.message}", throwable)
  }

  private val scope = CoroutineScope(viewModelScope.coroutineContext + exceptionHandler)

  private val readWriteDatabaseOpenHelper = ReadWriteDatabaseOpenHelper(application)
  private val readWriteDatabaseFlow: MutableStateFlow<SQLiteDatabase?> = MutableStateFlow(null)
  @RequiresApi(28) private val readOnlyDatabaseOpenHelper = ReadOnlyDatabaseOpenHelper(application)
  private val readOnlyDatabaseFlow: MutableStateFlow<SQLiteDatabase?> = MutableStateFlow(null)

  private val readWriteBundledDatabaseFlow: MutableStateFlow<SQLiteConnection?> =
    MutableStateFlow(null)
  private val readOnlyBundledDatabaseFlow: MutableStateFlow<SQLiteConnection?> =
    MutableStateFlow(null)

  init {
    scope.launch(IO) {
      val roomTables = roomDatabase.openHelper.readableDatabase.use { it.getTables() }
      roomDatabase.invalidationTracker.addObserver(RoomObserver("Android", roomTables))
      roomBundledDatabase.invalidationTracker.addObserver(RoomObserver("Bundled", roomTables))

      sqldelightDriver.getTables().forEach {
        sqldelightDriver.addListener(it, listener = SqlDelightListener(it))
      }
    }
  }

  val readWriteDatabaseState: StateFlow<Boolean> =
    readWriteDatabaseFlow.map { it != null }.stateIn(viewModelScope, WhileUiSubscribed, false)

  val readOnlyDatabaseState: StateFlow<Boolean> =
    readOnlyDatabaseFlow.map { it != null }.stateIn(viewModelScope, WhileUiSubscribed, false)

  override fun doOpenReadWriteDatabase() {
    scope.launch(IO) { readWriteDatabaseFlow.value = readWriteDatabaseOpenHelper.writableDatabase }
  }

  override fun doCloseReadWriteDatabase() {
    scope.launch(IO) {
      readWriteDatabaseFlow.value?.close()
      readWriteDatabaseFlow.value = null
    }
  }

  @RequiresApi(28)
  override fun doOpenReadOnlyDatabase() {
    scope.launch(IO) { readOnlyDatabaseFlow.value = readOnlyDatabaseOpenHelper.readableDatabase }
  }

  override fun doCloseReadOnlyDatabase() {
    scope.launch(IO) {
      readOnlyDatabaseFlow.value?.close()
      readOnlyDatabaseFlow.value = null
    }
  }

  val readWriteBundledDatabaseState: StateFlow<Boolean> =
    readWriteBundledDatabaseFlow
      .map { it != null }
      .stateIn(viewModelScope, WhileUiSubscribed, false)

  val readOnlyBundledDatabaseState: StateFlow<Boolean> =
    readOnlyBundledDatabaseFlow.map { it != null }.stateIn(viewModelScope, WhileUiSubscribed, false)

  override fun doOpenReadWriteBundledDatabase() {
    scope.launch(IO) { readWriteBundledDatabaseFlow.value = openBundledDatabase(false) }
  }

  override fun doCloseReadWriteBundledDatabase() {
    scope.launch(IO) {
      readWriteBundledDatabaseFlow.value?.close()
      readWriteBundledDatabaseFlow.value = null
    }
  }

  override fun doOpenReadOnlyBundledDatabase() {
    scope.launch(IO) { readOnlyBundledDatabaseFlow.value = openBundledDatabase(true) }
  }

  override fun doCloseReadOnlyBundledDatabase() {
    scope.launch(IO) {
      readOnlyBundledDatabaseFlow.value?.close()
      readOnlyBundledDatabaseFlow.value = null
    }
  }

  override fun addUserRoom() {
    scope.launch(IO) { roomDatabase.userDao().insert(RoomUserEntity(name = randomUserName())) }
  }

  override fun addUserRoomBundled() {
    scope.launch(IO) {
      roomBundledDatabase.userDao().insert(RoomUserEntity(name = randomUserName()))
    }
  }

  override fun addUserSqlDelight() {
    scope.launch(IO) { sqlDelightDatabase.sqlDelightDatabaseQueries.insert(randomUserName()) }
  }

  override fun addUserNative() {
    scope.launch(IO) {
      readWriteDatabaseFlow.value = readWriteDatabaseOpenHelper.writableDatabase
      readWriteDatabaseFlow.value?.insert(
        "Users",
        null,
        ContentValues().apply { put("name", randomUserName()) },
      )
    }
  }

  override fun addUserBundled() {
    scope.launch(IO) {
      val connection = readWriteBundledDatabaseFlow.value ?: openBundledDatabase(false)
      connection.prepare(INSERT_USER).use {
        it.bindText(1, randomUserName())
        it.step()
      }
      readWriteBundledDatabaseFlow.value = connection
    }
  }

  /**
   * A [SQLiteOpenHelper] that manages [NATIVE_DATABASE_CREATE] as a read-write database.
   *
   * See [ReadOnlyDatabaseOpenHelper] which manages the same file as a readonly instance.
   */
  private class ReadWriteDatabaseOpenHelper(context: Context) :
    SQLiteOpenHelper(context, NATIVE_DATABASE, null, NATIVE_DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL(NATIVE_DATABASE_CREATE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
  }

  /**
   * A [SQLiteOpenHelper] that manages [NATIVE_DATABASE_CREATE] as a readonly database.
   *
   * See [ReadOnlyDatabaseOpenHelper] which manages the same file as a read-write instance.
   */
  @RequiresApi(28)
  private class ReadOnlyDatabaseOpenHelper(context: Context) :
    SQLiteOpenHelper(
      context,
      NATIVE_DATABASE,
      NATIVE_DATABASE_VERSION,
      OpenParams.Builder().setOpenFlags(OPEN_READONLY).build(),
    ) {

    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL(NATIVE_DATABASE_CREATE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
  }

  private inner class RoomObserver(private val driver: String, tables: List<String>) :
    InvalidationTracker.Observer(tables.toTypedArray()) {

    override fun onInvalidated(tables: Set<String>) {
      setSnack("Room ($driver) tables [${tables.joinToString { it }}]  updated")
    }
  }

  private inner class SqlDelightListener(val table: String) : Query.Listener {
    override fun queryResultsChanged() {
      setSnack("SqlDelight table `$table` updated")
    }
  }

  private fun openBundledDatabase(isReadOnly: Boolean): SQLiteConnection {
    val flags = if (isReadOnly) SQLITE_OPEN_READONLY else SQLITE_OPEN_READWRITE
    val path = application.getDatabasePath("bundled-database.db").path
    // Open a writeable database and create table if needed, then close it
    bundledSQLiteDriver.open(path).use { it.execSQL(NATIVE_DATABASE_CREATE) }

    return bundledSQLiteDriver.open(path, flags)
  }
}

private fun SupportSQLiteDatabase.getTables() =
  query(QUERY_TABLES).use { cursor ->
    buildList {
      while (cursor.moveToNext()) {
        this.add(cursor.getString(0))
      }
    }
  }

private fun AndroidSqliteDriver.getTables() =
  executeQuery(
      null,
      QUERY_TABLES,
      { cursor ->
        val tables = buildList {
          while (cursor.next().value) {
            val table = cursor.getString(0)
            if (table != null) {
              add(table)
            }
          }
        }
        QueryResult.Value(tables)
      },
      0,
    )
    .value

private fun randomUserName() = "User ${Random.nextInt(0, 100)}"
