/*
 * Copyright 2019 The Android Open Source Project
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
package com.android.tools.appinspection.database

import android.annotation.SuppressLint
import android.app.Application
import android.database.Cursor.FIELD_TYPE_BLOB
import android.database.Cursor.FIELD_TYPE_FLOAT
import android.database.Cursor.FIELD_TYPE_INTEGER
import android.database.Cursor.FIELD_TYPE_NULL
import android.database.Cursor.FIELD_TYPE_STRING
import android.database.CursorWrapper
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteClosable
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteStatement
import android.os.Build
import android.os.CancellationSignal
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.inspection.ArtTooling.EntryHook
import androidx.inspection.ArtTooling.ExitHook
import androidx.inspection.Connection
import androidx.inspection.Inspector
import androidx.inspection.InspectorEnvironment
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.inspection.SqliteInspectorProtocol.AcquireDatabaseLockCommand
import androidx.sqlite.inspection.SqliteInspectorProtocol.AcquireDatabaseLockResponse
import androidx.sqlite.inspection.SqliteInspectorProtocol.AdditionalDriver
import androidx.sqlite.inspection.SqliteInspectorProtocol.CellValue
import androidx.sqlite.inspection.SqliteInspectorProtocol.Column
import androidx.sqlite.inspection.SqliteInspectorProtocol.Command
import androidx.sqlite.inspection.SqliteInspectorProtocol.Command.OneOfCase.ACQUIRE_DATABASE_LOCK
import androidx.sqlite.inspection.SqliteInspectorProtocol.Command.OneOfCase.GET_SCHEMA
import androidx.sqlite.inspection.SqliteInspectorProtocol.Command.OneOfCase.KEEP_DATABASES_OPEN
import androidx.sqlite.inspection.SqliteInspectorProtocol.Command.OneOfCase.QUERY
import androidx.sqlite.inspection.SqliteInspectorProtocol.Command.OneOfCase.RELEASE_DATABASE_LOCK
import androidx.sqlite.inspection.SqliteInspectorProtocol.Command.OneOfCase.TRACK_DATABASES
import androidx.sqlite.inspection.SqliteInspectorProtocol.DatabaseClosedEvent
import androidx.sqlite.inspection.SqliteInspectorProtocol.DatabaseOpenedEvent
import androidx.sqlite.inspection.SqliteInspectorProtocol.DatabasePossiblyChangedEvent
import androidx.sqlite.inspection.SqliteInspectorProtocol.ErrorContent
import androidx.sqlite.inspection.SqliteInspectorProtocol.ErrorContent.ErrorCode
import androidx.sqlite.inspection.SqliteInspectorProtocol.ErrorContent.ErrorCode.ERROR_UNKNOWN
import androidx.sqlite.inspection.SqliteInspectorProtocol.ErrorContent.ErrorCode.ERROR_UNRECOGNISED_COMMAND
import androidx.sqlite.inspection.SqliteInspectorProtocol.ErrorOccurredEvent
import androidx.sqlite.inspection.SqliteInspectorProtocol.ErrorOccurredResponse
import androidx.sqlite.inspection.SqliteInspectorProtocol.ErrorRecoverability
import androidx.sqlite.inspection.SqliteInspectorProtocol.Event
import androidx.sqlite.inspection.SqliteInspectorProtocol.GetSchemaCommand
import androidx.sqlite.inspection.SqliteInspectorProtocol.GetSchemaResponse
import androidx.sqlite.inspection.SqliteInspectorProtocol.KeepDatabasesOpenCommand
import androidx.sqlite.inspection.SqliteInspectorProtocol.KeepDatabasesOpenResponse
import androidx.sqlite.inspection.SqliteInspectorProtocol.QueryCommand
import androidx.sqlite.inspection.SqliteInspectorProtocol.QueryParameterValue
import androidx.sqlite.inspection.SqliteInspectorProtocol.QueryResponse
import androidx.sqlite.inspection.SqliteInspectorProtocol.ReleaseDatabaseLockCommand
import androidx.sqlite.inspection.SqliteInspectorProtocol.ReleaseDatabaseLockResponse
import androidx.sqlite.inspection.SqliteInspectorProtocol.Response
import androidx.sqlite.inspection.SqliteInspectorProtocol.Row
import androidx.sqlite.inspection.SqliteInspectorProtocol.Table
import androidx.sqlite.inspection.SqliteInspectorProtocol.TrackDatabasesCommand
import androidx.sqlite.inspection.SqliteInspectorProtocol.TrackDatabasesResponse
import com.android.tools.appinspection.database.EntryExitMatchingHookRegistry.OnExitCallback
import com.android.tools.appinspection.database.SqliteInspectionExecutors.submit
import com.android.tools.appinspection.database.Utils.isDatabase
import com.android.tools.appinspection.database.androidx.AndroidXDatabase
import com.android.tools.appinspection.database.androidx.SQLiteStatementWrapper
import com.android.tools.appinspection.database.framework.FrameworkDatabase
import com.android.tools.idea.protobuf.ByteString
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.text.RegexOption.IGNORE_CASE
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.asCoroutineDispatcher

private const val OPEN_DATABASE_COMMAND_SIG_API_11 =
  "openDatabase" +
    "(" +
    "Ljava/lang/String;" +
    "Landroid/database/sqlite/SQLiteDatabase\$CursorFactory;" +
    "I" +
    "Landroid/database/DatabaseErrorHandler;" +
    ")" +
    "Landroid/database/sqlite/SQLiteDatabase;"

private const val OPEN_DATABASE_COMMAND_SIG_API_27 =
  "openDatabase" +
    "(" +
    "Ljava/io/File;" +
    "Landroid/database/sqlite/SQLiteDatabase\$OpenParams;" +
    ")" +
    "Landroid/database/sqlite/SQLiteDatabase;"

private const val CREATE_IN_MEMORY_DATABASE_COMMAND_SIG_API_27 =
  "createInMemory" +
    "(" +
    "Landroid/database/sqlite/SQLiteDatabase\$OpenParams;" +
    ")" +
    "Landroid/database/sqlite/SQLiteDatabase;"

private const val ALL_REFERENCES_RELEASE_COMMAND_SIGNATURE = "onAllReferencesReleased()V"

// SQLiteStatement methods
private val SQLITE_STATEMENT_EXECUTE_METHODS_SIGNATURES: List<String> =
  mutableListOf("execute()V", "executeInsert()J", "executeUpdateDelete()I")

private const val ANDROIDX_DRIVER_OPEN_WITH_FLAGS_SIG =
  "open(Ljava/lang/String;I)Landroidx/sqlite/SQLiteConnection;"

private const val ANDROIDX_DRIVER_OPEN_SIG =
  "open(Ljava/lang/String;)Landroidx/sqlite/SQLiteConnection;"

private const val ANDROIDX_CONNECTION_CLOSE_SIG = "close()V"

private const val ANDROIDX_CONNECTION_PREPARE_SIG =
  "prepare(Ljava/lang/String;)Landroidx/sqlite/SQLiteStatement;"

private val INVALIDATION_MIN_INTERVAL = 1.seconds

// Note: this only works on API26+ because of pragma_* functions
// TODO: replace with a resource file
// language=SQLite
private const val QUERY_TABLE_INFO =
  """
    select
      m.type as type,
      m.name as tableName,
      ti.name as columnName,
      ti.type as columnType,
      [notnull],
      pk,
      ifnull([unique], 0) as [unique],
      m.sql
    from sqlite_master AS m, pragma_table_info(m.name) as ti
    left outer join
      (
        select tableName, name as columnName, ti.[unique]
        from
          (
            select m.name as tableName, il.name as indexName, il.[unique]
            from
              sqlite_master AS m,
              pragma_index_list(m.name) AS il,
              pragma_index_info(il.name) as ii
            where il.[unique] = 1
            group by il.name
            having count(*) = 1  -- countOfColumnsInIndex=1
          )
            as ti,  -- tableName|indexName|unique : unique=1 and countOfColumnsInIndex=1
          pragma_index_info(ti.indexName)
      )
        as tci  -- tableName|columnName|unique : unique=1 and countOfColumnsInIndex=1
      on tci.tableName = m.name and tci.columnName = ti.name
    where m.type in ('table', 'view')
    order by type, tableName, ti.cid  -- cid = columnId
    """

// language=SQLite
private const val QUERY_TABLE_SQL = "select name, sql from sqlite_master"

private val HIDDEN_TABLES = setOf("android_metadata", "sqlite_sequence")

private val REGEX_WITHOUT_ROWID = "\\s*without\\s+rowid".toRegex(IGNORE_CASE)

private const val BUNDLED_DRIVER = "androidx.sqlite.driver.bundled.BundledSQLiteDriver"

/**
 * Inspector to work with SQLite databases
 *
 * TODO(aalbert): Propagate CancellationException where appropriate
 */
internal class SqliteInspector(
  connection: Connection,
  private val environment: InspectorEnvironment,
  throttlerCoroutineContext: CoroutineContext =
    environment.executors().io().asCoroutineDispatcher(),
  testMode: Boolean = false,
) : Inspector(connection) {
  @VisibleForTesting
  internal val databaseRegistry =
    DatabaseRegistry(::dispatchDatabaseOpenedEvent, ::dispatchDatabaseClosedEvent, testMode)
  private val databaseLockRegistry = DatabaseLockRegistry(databaseRegistry)
  private val ioExecutor = environment.executors().io()

  /** Utility instance that handles communication with Room's InvalidationTracker instances. */
  private val roomInvalidationRegistry = RoomInvalidationRegistry(environment)

  private val invalidations =
    listOf(
      roomInvalidationRegistry,
      SqlDelightInvalidation.create(environment.artTooling()),
      SqlDelight2Invalidation.create(environment.artTooling()),
    )

  private val throttler =
    RequestCollapsingThrottler(
      INVALIDATION_MIN_INTERVAL,
      ::dispatchDatabasePossiblyChangedEvent,
      throttlerCoroutineContext,
    )

  override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {
    try {
      val command = Command.parseFrom(data)
      when (command.oneOfCase) {
        TRACK_DATABASES -> handleTrackDatabases(command.trackDatabases, callback)
        GET_SCHEMA -> handleGetSchema(command.getSchema, callback)
        QUERY -> handleQuery(command.query, callback)
        KEEP_DATABASES_OPEN -> handleKeepDatabasesOpen(command.keepDatabasesOpen, callback)
        ACQUIRE_DATABASE_LOCK -> handleAcquireDatabaseLock(command.acquireDatabaseLock, callback)
        RELEASE_DATABASE_LOCK -> handleReleaseDatabaseLock(command.releaseDatabaseLock, callback)
        else ->
          callback.reply(
            createErrorOccurredResponse(
                "Unrecognised command type: " + command.oneOfCase.name,
                null,
                true,
                ERROR_UNRECOGNISED_COMMAND,
              )
              .toByteArray()
          )
      }
    } catch (exception: Throwable) {
      Log.w(TAG, "Unexpected error initializing database inspector", exception)
      callback.reply(
        createErrorOccurredResponse(
            "Unhandled Exception while processing the command: " + exception.message,
            stackTraceFromException(exception),
            null,
            ERROR_UNKNOWN,
          )
          .toByteArray()
      )
    }
  }

  override fun onDispose() {
    super.onDispose()
    databaseRegistry.dispose()
    databaseLockRegistry.dispose()
    throttler.dispose()
  }

  private fun CommandCallback.replyTrackDatabasesCommand(
    trackedDriverClasses: List<AdditionalDriverClasses>
  ) {
    val trackDatabasesResponseBuilder = TrackDatabasesResponse.newBuilder()
    trackedDriverClasses.forEach {
      trackDatabasesResponseBuilder.addTrackedAdditionalDrivers(
        AdditionalDriver.newBuilder()
          .setDriverClass(it.driverClass.name)
          .setConnectionClass(it.connectionClass.name)
      )
    }
    reply(
      Response.newBuilder().setTrackDatabases(trackDatabasesResponseBuilder).build().toByteArray()
    )
  }

  private fun handleTrackDatabases(command: TrackDatabasesCommand, callback: CommandCallback) {
    val hookRegistry = EntryExitMatchingHookRegistry(environment)
    val ignoreFrameworkApi = command.ignoreFrameworkApi
    if (!ignoreFrameworkApi) {
      registerFrameworkHooks(hookRegistry)
    }

    val bundledDriver = AdditionalDriver.newBuilder().setDriverClass(BUNDLED_DRIVER).build()
    val bundledClasses = bundledDriver.toClasses()
    if (bundledClasses != null) {
      registerAndroidXHooks(hookRegistry, bundledClasses)
    }

    val classes = command.additionalDriversList.mapNotNull { it.toClasses() }
    registerAndroidXHooks(hookRegistry, *classes.toTypedArray())

    callback.replyTrackDatabasesCommand(classes)

    // Check for database instances in memory
    val artTooling = environment.artTooling()
    if (!ignoreFrameworkApi) {
      for (instance in artTooling.findInstances(SQLiteDatabase::class.java)) {
        val database = FrameworkDatabase(instance)
        /* the race condition here will be handled by mDatabaseRegistry */
        if (instance.isOpen) {
          onDatabaseOpened(database)
        } else {
          onDatabaseClosed(database)
        }
      }
    }
    classes
      .map { it.connectionClass }
      .forEach {
        Log.i(TAG, "Finding instances of ${it.name}")
        artTooling.findInstances(it).forEach { sqlConnection ->
          val file = sqlConnection.getDatabasePath()

          val database = AndroidXDatabase(sqlConnection, file)
          if (database.isOpen()) {
            onDatabaseOpened(database)
          }
        }
      }

    if (command.forceOpen) {
      databaseRegistry.enableForceOpen()
    }
    if (command.ignoreFrameworkApi) {
      databaseRegistry.ignoreFrameworkApi()
    }
    // Check for database instances on disk
    for (instance in artTooling.findInstances(Application::class.java)) {
      for (name in instance.databaseList()) {
        val path = instance.getDatabasePath(name)
        if (path.isDatabase()) {
          databaseRegistry.notifyOnDiskDatabase(path.absolutePath)
        }
      }
    }
  }

  private fun registerFrameworkHooks(hookRegistry: EntryExitMatchingHookRegistry) {
    registerFrameworkOpenHooks()
    registerFrameworkCloseHooks(hookRegistry)
    registerFrameworkReleaseReferenceHooks()
    registerFrameworkInvalidationHooks(hookRegistry)
  }

  private fun registerAndroidXHooks(
    hookRegistry: EntryExitMatchingHookRegistry,
    vararg drivers: AdditionalDriverClasses,
  ) {
    try {
      registerAndroidXOpenHooks(hookRegistry, drivers.map { it.driverClass })
      val connectionClasses = drivers.map { it.connectionClass }
      registerAndroidXCloseHooks(hookRegistry, connectionClasses)
      registerAndroidXInvalidationHooks(hookRegistry, connectionClasses)
    } catch (_: NoClassDefFoundError) {
      Log.w(TAG, "Unexpected error instrumenting AndroidX hooks")
    }
  }

  /**
   * Secures a lock (transaction) on the database. Note that while the lock is in place, no changes
   * to the database are possible: - the lock prevents other threads from modifying the database, -
   * lock thread, on releasing the lock, rolls-back all changes (transaction is rolled-back).
   */
  // code inside the future is exception-proofed
  private fun handleAcquireDatabaseLock(
    command: AcquireDatabaseLockCommand,
    callback: CommandCallback,
  ) {
    val databaseId = command.databaseId
    val connection = acquireConnection(databaseId, callback) ?: return

    // Timeout is covered by mDatabaseLockRegistry
    submit(
      ioExecutor,
      Runnable {
        val lockId: Int
        try {
          lockId = databaseLockRegistry.acquireLock(databaseId, connection.database)
        } catch (e: Throwable) {
          processLockingException(callback, e, true)
          return@Runnable
        }
        callback.reply(
          Response.newBuilder()
            .setAcquireDatabaseLock(AcquireDatabaseLockResponse.newBuilder().setLockId(lockId))
            .build()
            .toByteArray()
        )
      },
    )
  }

  // code inside the future is exception-proofed
  private fun handleReleaseDatabaseLock(
    command: ReleaseDatabaseLockCommand,
    callback: CommandCallback,
  ) {
    // Timeout is covered by mDatabaseLockRegistry
    submit(
      ioExecutor,
      Runnable {
        try {
          databaseLockRegistry.releaseLock(command.lockId)
        } catch (e: Throwable) {
          processLockingException(callback, e, false)
          return@Runnable
        }
        callback.reply(
          Response.newBuilder()
            .setReleaseDatabaseLock(ReleaseDatabaseLockResponse.getDefaultInstance())
            .build()
            .toByteArray()
        )
      },
    )
  }

  /** @param isLockingStage provide true for acquiring a lock; false for releasing a lock */
  private fun processLockingException(
    callback: CommandCallback,
    exception: Throwable,
    isLockingStage: Boolean,
  ) {
    val errorCode =
      if (((exception is IllegalStateException) && exception.isAttemptAtUsingClosedDatabase()))
        ErrorCode.ERROR_DB_CLOSED_DURING_OPERATION
      else ErrorCode.ERROR_ISSUE_WITH_LOCKING_DATABASE

    val message =
      if (isLockingStage) "Issue while trying to lock the database for the export operation: "
      else "Issue while trying to unlock the database after the export operation: "

    val isRecoverable =
      if (isLockingStage) true // failure to lock the db should be recoverable
      else null // not sure if we can recover from a failure to unlock the db, so

    // UNKNOWN
    callback.reply(
      createErrorOccurredResponse(message, isRecoverable, exception, errorCode).toByteArray()
    )
  }

  private fun registerFrameworkOpenHooks() {
    val entryHook = EntryHook { _, args ->
      // args[0] is either a `String` or a `File`. Either way, `toString()` works
      databaseLockRegistry.waitForUnlockedDatabase(args[0].toString())
    }

    val exitHook =
      ExitHook<SQLiteDatabase> { database ->
        try {
          onDatabaseOpened(FrameworkDatabase(database))
        } catch (exception: Throwable) {
          connection.sendEvent(
            createErrorOccurredEvent(
                "Unhandled Exception while processing an onDatabaseAdded " +
                  "event: " +
                  exception.message,
                stackTraceFromException(exception),
                null,
                ErrorCode.ERROR_ISSUE_WITH_PROCESSING_NEW_DATABASE_CONNECTION,
              )
              .toByteArray()
          )
        }
        database
      }
    val artTooling = environment.artTooling()
    val clazz = SQLiteDatabase::class.java

    artTooling.registerEntryHook(clazz, OPEN_DATABASE_COMMAND_SIG_API_11, entryHook)
    artTooling.registerExitHook(clazz, OPEN_DATABASE_COMMAND_SIG_API_11, exitHook)
    if (Build.VERSION.SDK_INT >= 27) {
      artTooling.registerEntryHook(clazz, OPEN_DATABASE_COMMAND_SIG_API_27, entryHook)
      artTooling.registerExitHook(clazz, OPEN_DATABASE_COMMAND_SIG_API_27, exitHook)
      artTooling.registerExitHook(clazz, CREATE_IN_MEMORY_DATABASE_COMMAND_SIG_API_27, exitHook)
    }
  }

  private fun registerAndroidXOpenHooks(
    hookRegistry: EntryExitMatchingHookRegistry,
    driverClasses: List<Class<SQLiteDriver>>,
  ) {
    driverClasses.forEach { registerAndroidXOpenHooks(hookRegistry, it) }
  }

  private fun registerAndroidXOpenHooks(
    hookRegistry: EntryExitMatchingHookRegistry,
    cls: Class<out SQLiteDriver>,
  ) {
    Log.i(TAG, "registerAndroidXOpenHooks: ${cls.name}")
    val hasFlags = cls.name == BUNDLED_DRIVER
    val entryHook = EntryHook { _, args ->
      databaseLockRegistry.waitForUnlockedDatabase(args[0].toString())
    }
    val onExitCallback =
      OnExitCallback<SQLiteDriver, SQLiteConnection> { _, args, result ->
        val sqliteConnection = result ?: return@OnExitCallback null
        val path = args[0] as String
        val flags = if (hasFlags) args[1] as Int else 0

        try {
          onDatabaseOpened(AndroidXDatabase(sqliteConnection, path, flags))
        } catch (exception: Throwable) {
          connection.sendEvent(
            createErrorOccurredEvent(
                "Unhandled Exception while processing an onDatabaseAdded " +
                  "event: " +
                  exception.message,
                stackTraceFromException(exception),
                null,
                ErrorCode.ERROR_ISSUE_WITH_PROCESSING_NEW_DATABASE_CONNECTION,
              )
              .toByteArray()
          )
        }
        sqliteConnection
      }
    val sig = if (hasFlags) ANDROIDX_DRIVER_OPEN_WITH_FLAGS_SIG else ANDROIDX_DRIVER_OPEN_SIG
    hookRegistry.registerHook(cls, sig, entryHook, onExitCallback)
  }

  /**
   * Tracking potential database closed events via [ ][.ALL_REFERENCES_RELEASE_COMMAND_SIGNATURE]
   */
  private fun registerFrameworkCloseHooks(hookRegistry: EntryExitMatchingHookRegistry) {
    hookRegistry.registerHook<SQLiteDatabase, Unit>(
      SQLiteDatabase::class.java,
      ALL_REFERENCES_RELEASE_COMMAND_SIGNATURE,
    ) { thisObject, _, _ ->
      if (thisObject is SQLiteDatabase) {
        onDatabaseClosed(FrameworkDatabase(thisObject))
      }
    }
  }

  private fun registerFrameworkReleaseReferenceHooks() {
    environment.artTooling().registerEntryHook(SQLiteClosable::class.java, "releaseReference()V") {
      thisObject,
      _ ->
      if (thisObject is SQLiteDatabase) {
        databaseRegistry.notifyReleaseReference(FrameworkDatabase(thisObject))
      }
    }
  }

  private fun registerFrameworkInvalidationHooks(hookRegistry: EntryExitMatchingHookRegistry) {
    registerInvalidationHooksSqliteStatement()
    registerInvalidationHooksTransaction()
    registerInvalidationHooksSQLiteCursor(hookRegistry)
  }

  /**
   * Triggering invalidation on [SQLiteDatabase.endTransaction] allows us to avoid showing incorrect
   * stale values that could originate from a mid-transaction query.
   *
   * TODO: track if transaction committed or rolled back by observing if
   *   [ ][SQLiteDatabase.setTransactionSuccessful] was called
   */
  private fun registerInvalidationHooksTransaction() {
    environment.artTooling().registerExitHook<Any>(
      SQLiteDatabase::class.java,
      "endTransaction()V",
    ) { result ->
      throttler.submitRequest()
      result
    }
  }

  /**
   * Invalidation hooks triggered by:
   * * [SQLiteStatement.execute]
   * * [SQLiteStatement.executeInsert]
   * * [SQLiteStatement.executeUpdateDelete]
   */
  private fun registerInvalidationHooksSqliteStatement() {
    for (method in SQLITE_STATEMENT_EXECUTE_METHODS_SIGNATURES) {
      environment.artTooling().registerExitHook<Any>(SQLiteStatement::class.java, method) { result
        ->
        throttler.submitRequest()
        result
      }
    }
  }

  /**
   * Invalidation hooks triggered by [SQLiteCursor.close] which means that the cursor's query was
   * executed.
   *
   * In order to access cursor's query, we also use [SQLiteDatabase.rawQueryWithFactory] which takes
   * a query String and constructs a cursor based on it.
   */
  private fun registerInvalidationHooksSQLiteCursor(hookRegistry: EntryExitMatchingHookRegistry) {
    // TODO: add active pruning via Cursor#close listener

    val trackedCursors = Collections.synchronizedMap(WeakHashMap<SQLiteCursor, Void?>())

    val rawQueryMethodSignature =
      ("rawQueryWithFactory(" +
        "Landroid/database/sqlite/SQLiteDatabase\$CursorFactory;" +
        "Ljava/lang/String;" +
        "[Ljava/lang/String;" +
        "Ljava/lang/String;" +
        "Landroid/os/CancellationSignal;" +
        ")Landroid/database/Cursor;")
    hookRegistry.registerHook<SQLiteDatabase, android.database.Cursor>(
      SQLiteDatabase::class.java,
      rawQueryMethodSignature,
    ) { _, args, result ->
      val query = stringParam(args[1]!!)
      val cursor = cursorParam(result)

      // Only track cursors that might modify the database.
      // TODO: handle PRAGMA select queries, e.g. PRAGMA_TABLE_INFO
      if (
        cursor != null &&
          query != null &&
          DatabaseUtils.getSqlStatementType(query) != DatabaseUtils.STATEMENT_SELECT
      ) {
        trackedCursors[cursor] = null
      }
      result
    }

    environment.artTooling().registerEntryHook(SQLiteCursor::class.java, "close()V") { thisObject, _
      ->
      if (trackedCursors.containsKey(thisObject)) {
        throttler.submitRequest()
      }
    }
  }

  private fun registerAndroidXCloseHooks(
    hookRegistry: EntryExitMatchingHookRegistry,
    connectionClasses: List<Class<SQLiteConnection>>,
  ) {
    connectionClasses.forEach { registerAndroidXCloseHooks(hookRegistry, it) }
  }

  private fun registerAndroidXCloseHooks(
    hookRegistry: EntryExitMatchingHookRegistry,
    cls: Class<out SQLiteConnection>,
  ) {
    Log.i(TAG, "registerAndroidXCloseHooks: ${cls.name}")
    val databasePath = AtomicReference<String?>(null)
    // We need to hook both entry and exit hooks because we need to extract the database path from
    // the connection
    // before is closes, and we need the connection to closed when we call onDatabaseClosed
    hookRegistry.registerHook<SQLiteConnection, Unit>(
      cls,
      ANDROIDX_CONNECTION_CLOSE_SIG,
      { connection, _ ->
        if (connection is SQLiteConnection) {
          databasePath.set(connection.getDatabasePath())
        }
      },
    ) { connection, _, _ ->
      val path = databasePath.get()
      if (connection != null && path != null) {
        onDatabaseClosed(AndroidXDatabase(connection, path))
      }
    }
  }

  private fun registerAndroidXInvalidationHooks(
    hookRegistry: EntryExitMatchingHookRegistry,
    connectionClasses: List<Class<SQLiteConnection>>,
  ) {
    connectionClasses.forEach { registerAndroidXInvalidationHooks(hookRegistry, it) }
  }

  private fun registerAndroidXInvalidationHooks(
    hookRegistry: EntryExitMatchingHookRegistry,
    cls: Class<out SQLiteConnection>,
  ) {
    Log.i(TAG, "registerAndroidXInvalidationHooks: ${cls.name}")
    hookRegistry.registerHook<SQLiteConnection, androidx.sqlite.SQLiteStatement>(
      cls,
      ANDROIDX_CONNECTION_PREPARE_SIG,
    ) { _, args, result ->
      // if the prepared statement is not a SELECT, we wrap it with a wrapper that triggers
      // invalidation when step() is
      // called
      val statement = result ?: return@registerHook null
      val sql = args.firstOrNull() as? String ?: return@registerHook statement
      val type = DatabaseUtils.getSqlStatementType(sql)
      when {
        type == DatabaseUtils.STATEMENT_SELECT -> statement
        else -> SQLiteStatementWrapper(statement) { throttler.submitRequest() }
      }
    }
  }

  // Gets a SQLiteCursor from a passed-in Object (if possible)
  private fun cursorParam(cursor: Any?): SQLiteCursor? {
    if (cursor is SQLiteCursor) {
      return cursor
    }

    if (cursor is CursorWrapper) {
      return cursorParam(cursor.wrappedCursor)
    }

    // TODO: add support for more cursor types
    Log.w(
      SqliteInspector::class.java.name,
      String.format("Unsupported Cursor type: %s. Invalidation might not work correctly.", cursor),
    )
    return null
  }

  // Gets a String from a passed-in Object (if possible)
  private fun stringParam(string: Any): String? {
    return string as? String
  }

  private fun dispatchDatabaseOpenedEvent(
    databaseId: Int,
    path: String,
    isForced: Boolean,
    isReadOnly: Boolean,
    apiClassName: String,
  ) {
    Log.v(HIDDEN_TAG, "dispatchDatabaseOpenedEvent: ${path.substringAfterLast("/")}")
    connection.sendEvent(
      Event.newBuilder()
        .setDatabaseOpened(
          DatabaseOpenedEvent.newBuilder()
            .setDatabaseId(databaseId)
            .setPath(path)
            .setIsForcedConnection(isForced)
            .setIsReadOnly(isReadOnly)
            .setApiClassName(apiClassName)
        )
        .build()
        .toByteArray()
    )
  }

  private fun dispatchDatabaseClosedEvent(databaseId: Int, path: String) {
    Log.v(HIDDEN_TAG, "dispatchDatabaseClosedEvent: ${path.substringAfterLast("/")}")
    connection.sendEvent(
      Event.newBuilder()
        .setDatabaseClosed(DatabaseClosedEvent.newBuilder().setDatabaseId(databaseId).setPath(path))
        .build()
        .toByteArray()
    )
  }

  private fun dispatchDatabasePossiblyChangedEvent() {
    connection.sendEvent(
      Event.newBuilder()
        .setDatabasePossiblyChanged(DatabasePossiblyChangedEvent.getDefaultInstance())
        .build()
        .toByteArray()
    )
  }

  // code inside the future is exception-proofed
  private fun handleGetSchema(command: GetSchemaCommand, callback: CommandCallback) {
    val connection = acquireConnection(command.databaseId, callback) ?: return

    // TODO: consider a timeout
    submit(connection.executor) { callback.reply(querySchema(connection.database).toByteArray()) }
  }

  private fun handleQuery(command: QueryCommand, callback: CommandCallback) {
    val connection = acquireConnection(command.databaseId, callback) ?: return

    val cancellationSignal = CancellationSignal()
    val executor = connection.executor
    // TODO: consider a timeout
    val future: Future<*> =
      submit(executor) {
        val params = parseQueryParameterValues(command)
        var cursor: Cursor? = null
        try {
          cursor = rawQuery(connection.database, command.query, params, cancellationSignal)

          var responseSizeLimitHint = command.responseSizeLimitHint
          // treating unset field as unbounded
          if (responseSizeLimitHint <= 0) responseSizeLimitHint = Long.MAX_VALUE

          val columnNames = listOf(*cursor.getColumnNames())
          callback.reply(
            Response.newBuilder()
              .setQuery(
                QueryResponse.newBuilder()
                  .setIsForcedConnection(databaseRegistry.isForcedConnection(connection.database))
                  .addAllRows(convert(cursor, responseSizeLimitHint))
                  .addAllColumnNames(columnNames)
                  .build()
              )
              .build()
              .toByteArray()
          )
          triggerInvalidation(command.query)
        } catch (e: SQLiteException) {
          callback.reply(
            createErrorOccurredResponse(e, true, ErrorCode.ERROR_ISSUE_WITH_PROCESSING_QUERY)
              .toByteArray()
          )
        } catch (e: IllegalArgumentException) {
          callback.reply(
            createErrorOccurredResponse(e, true, ErrorCode.ERROR_ISSUE_WITH_PROCESSING_QUERY)
              .toByteArray()
          )
        } catch (e: IllegalStateException) {
          if (e.isAttemptAtUsingClosedDatabase()) {
            callback.reply(
              createErrorOccurredResponse(e, true, ErrorCode.ERROR_DB_CLOSED_DURING_OPERATION)
                .toByteArray()
            )
          } else {
            callback.reply(createErrorOccurredResponse(e, null, ERROR_UNKNOWN).toByteArray())
          }
        } catch (e: Throwable) {
          callback.reply(createErrorOccurredResponse(e, null, ERROR_UNKNOWN).toByteArray())
        } finally {
          cursor?.close()
        }
      }
    callback.addCancellationListener(environment.executors().primary()) {
      cancellationSignal.cancel()
      future.cancel(true)
    }
  }

  private fun triggerInvalidation(query: String) {
    if (DatabaseUtils.getSqlStatementType(query) != DatabaseUtils.STATEMENT_SELECT) {
      for (invalidation in invalidations) {
        invalidation.triggerInvalidations()
      }
    }
  }

  private fun handleKeepDatabasesOpen(
    keepDatabasesOpen: KeepDatabasesOpenCommand,
    callback: CommandCallback,
  ) {
    // Acknowledge the command
    callback.reply(
      Response.newBuilder()
        .setKeepDatabasesOpen(KeepDatabasesOpenResponse.getDefaultInstance())
        .build()
        .toByteArray()
    )

    databaseRegistry.notifyKeepOpenToggle(keepDatabasesOpen.setEnabled)
  }

  /**
   * Tries to find a database for an id. If no such database is found, it replies with an [ ] via
   * the `callback` provided.
   *
   * The race condition can be mitigated by clients by securing a lock synchronously with no other
   * queries in place.
   *
   * @return null if no database found for the provided id. A database reference otherwise.
   *
   * TODO: remove race condition (affects WAL=off) - lock request is received and in the process of
   *   being secured - query request is received and since no lock in place, receives an IO
   *   Executor - lock request completes and holds a lock on the database - query cannot run because
   *   there is a lock in place
   */
  private fun acquireConnection(databaseId: Int, callback: CommandCallback): DatabaseConnection? {
    val connection = databaseLockRegistry.getConnection(databaseId)
    if (connection != null) {
      // With WAL enabled, we prefer to use the IO executor. With WAL off we don't have a
      // choice and must use the executor that has a lock (transaction) on the database.
      return if (connection.database.isWriteAheadLoggingEnabled())
        DatabaseConnection(connection.database, ioExecutor)
      else connection
    }

    val database = databaseRegistry.getConnection(databaseId)
    if (database == null) {
      replyNoDatabaseWithId(callback, databaseId)
      return null
    }

    // Given no lock, IO executor is appropriate.
    return DatabaseConnection(database, ioExecutor)
  }

  private fun replyNoDatabaseWithId(callback: CommandCallback, databaseId: Int) {
    val message =
      String.format(
        "Unable to perform an operation on database (id=%s)." +
          " The database may have already been closed.",
        databaseId,
      )
    callback.reply(
      createErrorOccurredResponse(
          message,
          null,
          true,
          ErrorCode.ERROR_NO_OPEN_DATABASE_WITH_REQUESTED_ID,
        )
        .toByteArray()
    )
  }

  private fun querySchema(database: Database): Response {
    var cursor: Cursor? = null
    try {
      val withoutRowidMap = getWithoutRowIdMap(database)
      cursor = rawQuery(database, QUERY_TABLE_INFO, arrayOfNulls(0), null)
      val schemaBuilder =
        GetSchemaResponse.newBuilder()
          .setIsForcedConnection(databaseRegistry.isForcedConnection(database))

      val objectTypeIx = cursor.getColumnIndex("type") // view or table
      val tableNameIx = cursor.getColumnIndex("tableName")
      val columnNameIx = cursor.getColumnIndex("columnName")
      val typeIx = cursor.getColumnIndex("columnType")
      val pkIx = cursor.getColumnIndex("pk")
      val notNullIx = cursor.getColumnIndex("notnull")
      val uniqueIx = cursor.getColumnIndex("unique")

      var tableBuilder: Table.Builder? = null
      while (cursor.moveToNext()) {
        val tableName = cursor.getString(tableNameIx)

        // ignore certain tables
        if (HIDDEN_TABLES.contains(tableName)) {
          continue
        }

        // check if getting data for a new table or appending columns to the current one
        if (tableBuilder == null || tableBuilder.name != tableName) {
          if (tableBuilder != null) {
            schemaBuilder.addTables(tableBuilder.build())
          }
          tableBuilder =
            Table.newBuilder()
              .setName(tableName)
              .setIsView("view".equals(cursor.getString(objectTypeIx), ignoreCase = true))
              .setWithoutRowid(withoutRowidMap.getOrDefault(tableName, true))
        }

        // append column information to the current table info
        tableBuilder.addColumns(
          Column.newBuilder()
            .setName(cursor.getString(columnNameIx))
            .setType(cursor.getString(typeIx))
            .setPrimaryKey(cursor.getInt(pkIx))
            .setIsNotNull(cursor.getInt(notNullIx) > 0)
            .setIsUnique(cursor.getInt(uniqueIx) > 0)
            .build()
        )
      }
      if (tableBuilder != null) {
        schemaBuilder.addTables(tableBuilder.build())
      }

      return Response.newBuilder().setGetSchema(schemaBuilder.build()).build()
    } catch (e: IllegalStateException) {
      return if (e.isAttemptAtUsingClosedDatabase()) {
        createErrorOccurredResponse(e, true, ErrorCode.ERROR_DB_CLOSED_DURING_OPERATION)
      } else {
        createErrorOccurredResponse(e, null, ERROR_UNKNOWN)
      }
    } catch (e: Throwable) {
      return createErrorOccurredResponse(e, null, ERROR_UNKNOWN)
    } finally {
      cursor?.close()
    }
  }

  private fun getWithoutRowIdMap(database: Database): Map<String, Boolean> {
    return buildMap {
      database.rawQuery(QUERY_TABLE_SQL, emptyArray(), null).use { cursor ->
        while (cursor.moveToNext()) {
          val tableName = cursor.getString(0)!!
          val sql = cursor.getString(1)
          val withoutRowId = sql?.substringAfterLast(')')?.contains(REGEX_WITHOUT_ROWID) == true
          put(tableName, withoutRowId)
        }
      }
    }
  }

  private fun onDatabaseOpened(database: Database) {
    try {
      roomInvalidationRegistry.invalidateCache()
      databaseRegistry.notifyDatabaseOpened(database)
    } catch (exception: Throwable) {
      connection.sendEvent(
        createErrorOccurredEvent(
            "Unhandled Exception while processing an onDatabaseAdded " +
              "event: " +
              exception.message,
            stackTraceFromException(exception),
            null,
            ErrorCode.ERROR_ISSUE_WITH_PROCESSING_NEW_DATABASE_CONNECTION,
          )
          .toByteArray()
      )
    }
  }

  private fun onDatabaseClosed(database: Database) {
    databaseRegistry.notifyAllDatabaseReferencesReleased(database)
  }

  @Suppress("SameParameterValue")
  private fun createErrorOccurredEvent(
    message: String?,
    stackTrace: String?,
    isRecoverable: Boolean?,
    errorCode: ErrorCode,
  ): Event {
    return Event.newBuilder()
      .setErrorOccurred(
        ErrorOccurredEvent.newBuilder()
          .setContent(createErrorContentMessage(message, stackTrace, isRecoverable, errorCode))
          .build()
      )
      .build()
  }

  private fun SQLiteConnection.getDatabasePath(): String {
    val application = environment.artTooling().findInstances(Application::class.java).firstOrNull()
    val file =
      prepare("SELECT file FROM pragma_database_list() WHERE name = 'main'").use {
        it.step()
        val filename = it.getText(0)
        // The Application database path is under "/data/user/<user>" but pragma_database_list
        // returns "/data/data"
        // paths, so we need to get the user id and change the path so it matches.
        val user =
          application
            ?.getSystemService(UserManager::class.java)
            ?.getSerialNumberForUser(Process.myUserHandle())
        when (user) {
          null -> filename
          else -> filename.replace("/data/data/", "/data/user/$user/")
        }
      }
    return file
  }

  /**
   * Provides a reference to the database and an executor to access the database.
   *
   * Executor is relevant in the context of locking, where a locked database with WAL disabled needs
   * to run queries on the thread that locked it.
   */
  internal class DatabaseConnection(val database: Database, val executor: Executor)

  companion object {

    @SuppressLint("Recycle") // For: "The cursor should be freed up after use with #close"
    private fun rawQuery(
      database: Database,
      queryText: String,
      params: Array<String?>,
      cancellationSignal: CancellationSignal?,
    ): Cursor {
      return database.rawQuery(queryText, params, cancellationSignal)
    }

    private fun parseQueryParameterValues(command: QueryCommand): Array<String?> {
      val params = arrayOfNulls<String>(command.queryParameterValuesCount)
      for (i in 0 until command.queryParameterValuesCount) {
        val param = command.getQueryParameterValues(i)
        when (param.oneOfCase) {
          QueryParameterValue.OneOfCase.STRING_VALUE -> params[i] = param.stringValue
          QueryParameterValue.OneOfCase.ONEOF_NOT_SET -> params[i] = null
          else ->
            throw IllegalArgumentException(
              "Unsupported parameter type. OneOfCase=" + param.oneOfCase
            )
        }
      }
      return params
    }

    /** @param responseSizeLimitHint expressed in bytes */
    private fun convert(cursor: Cursor, responseSizeLimitHint: Long): List<Row> {
      var responseSize: Long = 0
      val result: MutableList<Row> = ArrayList()
      val columnCount = cursor.getColumnCount()
      while (cursor.moveToNext() && responseSize < responseSizeLimitHint) {
        val rowBuilder = Row.newBuilder()
        for (i in 0 until columnCount) {
          val value = readValue(cursor, i)
          rowBuilder.addValues(value)
        }
        val row = rowBuilder.build()
        // Optimistically adding a row before checking the limit. Eliminates the case when a
        // misconfigured client (limit too low) is unable to fetch any results. Row size in
        // SQLite Android is limited to (~2MB), so the worst case scenario is very manageable.
        result.add(row)
        responseSize += row.serializedSize.toLong()
      }
      return result
    }

    private fun readValue(cursor: Cursor, index: Int): CellValue {
      val builder = CellValue.newBuilder()

      when (cursor.getType(index)) {
        FIELD_TYPE_NULL -> {}
        FIELD_TYPE_BLOB -> builder.blobValue = ByteString.copyFrom(cursor.getBlob(index))
        FIELD_TYPE_STRING -> builder.stringValue = cursor.getString(index)
        FIELD_TYPE_INTEGER -> builder.longValue = cursor.getLong(index)
        FIELD_TYPE_FLOAT -> builder.doubleValue = cursor.getDouble(index)
      }
      return builder.build()
    }

    private fun createErrorContentMessage(
      message: String?,
      stackTrace: String?,
      isRecoverable: Boolean?,
      errorCode: ErrorCode,
    ): ErrorContent {
      val builder = ErrorContent.newBuilder()
      if (message != null) {
        builder.message = message
      }
      if (stackTrace != null) {
        builder.stackTrace = stackTrace
      }
      val recoverability = ErrorRecoverability.newBuilder()
      if (isRecoverable != null) { // leave unset otherwise, which translates to 'unknown'
        recoverability.isRecoverable = isRecoverable
      }
      return builder.setRecoverability(recoverability.build()).setErrorCode(errorCode).build()
    }

    private fun createErrorOccurredResponse(
      exception: Throwable,
      isRecoverable: Boolean?,
      errorCode: ErrorCode,
    ): Response {
      return createErrorOccurredResponse("", isRecoverable, exception, errorCode)
    }

    private fun createErrorOccurredResponse(
      messagePrefix: String,
      isRecoverable: Boolean?,
      exception: Throwable,
      errorCode: ErrorCode,
    ): Response {
      var message = exception.message
      if (message == null) message = exception.toString()
      return createErrorOccurredResponse(
        messagePrefix + message,
        stackTraceFromException(exception),
        isRecoverable,
        errorCode,
      )
    }

    private fun createErrorOccurredResponse(
      message: String?,
      stackTrace: String?,
      isRecoverable: Boolean?,
      errorCode: ErrorCode,
    ): Response {
      return Response.newBuilder()
        .setErrorOccurred(
          ErrorOccurredResponse.newBuilder()
            .setContent(createErrorContentMessage(message, stackTrace, isRecoverable, errorCode))
        )
        .build()
    }

    private fun stackTraceFromException(exception: Throwable): String {
      val writer = StringWriter()
      exception.printStackTrace(PrintWriter(writer))
      return writer.toString()
    }
  }

  private class AdditionalDriverClasses(
    val driverClass: Class<SQLiteDriver>,
    val connectionClass: Class<SQLiteConnection>,
  )

  private fun AdditionalDriver.toClasses(): AdditionalDriverClasses? {
    val connectionClassName =
      connectionClass.ifEmpty { driverClass.replace("Driver", "Connection") }
    val driverClass = loadClass<SQLiteDriver>(driverClass) ?: return null
    val connectionClass = loadClass<SQLiteConnection>(connectionClassName) ?: return null
    return AdditionalDriverClasses(driverClass, connectionClass)
  }
}

private inline fun <reified T> loadClass(className: String): Class<T>? {
  return try {
    val cls = SqliteInspector::class.java.classLoader.loadClass(className)
    if (!T::class.java.isAssignableFrom(cls)) {
      throw ClassCastException("$className is not a ${T::class.java.name}")
    }
    @Suppress("UNCHECKED_CAST")
    cls as Class<T>
  } catch (e: ClassNotFoundException) {
    if (className != BUNDLED_DRIVER) {
      Log.w("SqliteInspector", "Can't load class '$className'", e)
    }
    null
  } catch (e: Throwable) {
    Log.w("SqliteInspector", "Can't load class '$className'", e)
    null
  }
}
