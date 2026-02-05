/*
 * Copyright 2020 The Android Open Source Project
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

import android.os.Build
import com.android.testutils.CloseablesRule
import com.android.tools.appinspection.common.testing.LogPrinterRule
import com.android.tools.appinspection.database.DatabaseRegistry.OnDatabaseClosedCallback
import com.android.tools.appinspection.database.DatabaseRegistry.OnDatabaseOpenedCallback
import com.android.tools.appinspection.database.DatabaseRegistryTest.DbEvent.DbClosedEvent
import com.android.tools.appinspection.database.DatabaseRegistryTest.DbEvent.DbOpenedEvent
import com.android.tools.appinspection.database.testing.DatabaseType
import com.android.tools.appinspection.database.testing.DatabaseType.ANDROID_X
import com.google.common.truth.Truth.assertThat
import kotlin.test.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.junit.rules.CloseGuardRule

/** Tests for [DatabaseRegistry] */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, minSdk = Build.VERSION_CODES.O, maxSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class DatabaseRegistryTest(private val databaseType: DatabaseType) {
  private val temporaryFolder = TemporaryFolder()
  private val closeablesRule = CloseablesRule()

  @get:Rule
  val rule: RuleChain = RuleChain.outerRule(CloseGuardRule()).around(closeablesRule).around(temporaryFolder).around(LogPrinterRule())

  private val events = mutableListOf<DbEvent>()

  @Test
  fun getConnection_withReadOnly_returnsReadOnly() {
    val databaseProvider = databaseType.getDatabaseProvider("${temporaryFolder.root}/db", closeablesRule)
    val registry = databaseRegistry(events)
    val readOnlyDb = databaseProvider.getReadOnlyDb()
    registry.notifyDatabaseOpened(readOnlyDb)

    val connection = registry.getConnection(events.first().id)

    assertThat(connection).isEqualTo(readOnlyDb)
  }

  @Test
  fun getConnection_withReadWrite_returnsReadWrite() {
    val databaseProvider = databaseType.getDatabaseProvider("${temporaryFolder.root}/db", closeablesRule)
    val registry = databaseRegistry(events)
    val readOnlyDb = databaseProvider.getReadOnlyDb()
    registry.notifyDatabaseOpened(readOnlyDb)
    val readWriteDb = databaseProvider.getReadWriteDb()
    registry.notifyDatabaseOpened(readWriteDb)
    registry.notifyDatabaseOpened(readOnlyDb)

    val connection = registry.getConnection(events.first().id)

    assertThat(connection).isEqualTo(readWriteDb)
  }

  @Test
  fun getConnection_withForced_returnsForced() {
    val databaseProvider = databaseType.getDatabaseProvider("${temporaryFolder.root}/db", closeablesRule)
    val registry = databaseRegistry(events, forceOpen = true)
    databaseProvider.createAndClose()
    registry.notifyOnDiskDatabase(databaseProvider.path)

    val connection = registry.getConnection(events.first().id) ?: fail("No database found")

    assertThat(registry.isForcedConnection(connection)).isTrue()
  }

  @Test
  fun getConnection_withForcedAndReadOnly_returnsForced() {
    val databaseProvider = databaseType.getDatabaseProvider("${temporaryFolder.root}/db", closeablesRule)
    val registry = databaseRegistry(events, forceOpen = true)
    databaseProvider.createAndClose()
    registry.notifyOnDiskDatabase(databaseProvider.path)
    registry.notifyDatabaseOpened(databaseProvider.getReadOnlyDb())

    val connection = registry.getConnection(events.first().id) ?: fail("No database found")

    assertThat(registry.isForcedConnection(connection)).isTrue()
  }

  @Test
  fun getConnection_withForcedAndReadWrite_returnsForced() {
    val databaseProvider = databaseType.getDatabaseProvider("${temporaryFolder.root}/db", closeablesRule)
    val registry = databaseRegistry(events, forceOpen = true)
    databaseProvider.createAndClose()
    registry.notifyOnDiskDatabase(databaseProvider.path)
    val readWriteDb = databaseProvider.getReadWriteDb()
    registry.notifyDatabaseOpened(readWriteDb)

    val connection = registry.getConnection(events.first().id) ?: fail("No database found")

    assertThat(connection).isEqualTo(readWriteDb)
  }

  @Test
  fun getConnection_alreadyOpen_notForced() {
    val path = "${temporaryFolder.root}/db"
    val databaseProvider = databaseType.getDatabaseProvider(path, closeablesRule)
    val registry = databaseRegistry(events, forceOpen = true)
    registry.notifyDatabaseOpened(databaseProvider.getReadWriteDb())
    registry.notifyOnDiskDatabase(databaseProvider.path)

    val id = registry.getIdForPath(path) ?: fail("No database found")
    val database = registry.getConnection(id) ?: fail("No database found")
    assertThat(registry.isForcedConnection(database)).isFalse()
  }

  @Test
  fun notifyKeepOpenToggle_doNotKeepForcedConnections() {
    val databaseProvider = databaseType.getDatabaseProvider("${temporaryFolder.root}/db", closeablesRule)
    val registry = databaseRegistry(events, forceOpen = true)
    databaseProvider.createAndClose()
    registry.notifyOnDiskDatabase(databaseProvider.path)

    registry.notifyKeepOpenToggle(true)

    assertThat(registry.keepOpenReferences).isEmpty()
  }

  @Test
  fun notifyKeepOpenToggle_replaceReadonlyWithWriteable() {
    // ANDROID_X Database doesn't support keep-open yet
    assumeTrue(databaseType != ANDROID_X)

    val databaseProvider = databaseType.getDatabaseProvider("${temporaryFolder.root}/db", closeablesRule)
    val readOnlyDb = databaseProvider.getReadOnlyDb(autoClose = false)
    val readWriteDb = databaseProvider.getReadWriteDb(autoClose = false)
    val registry = databaseRegistry(events)
    registry.notifyKeepOpenToggle(true)

    // Open and close a read-only database
    registry.notifyDatabaseOpenAndClose(readOnlyDb)
    val id = events.first().id

    assertThat(readOnlyDb.isOpen()).isTrue()
    assertThat(registry.getConnection(id)).isEqualTo(readOnlyDb)
    assertThat(registry.getDatabases(id)).containsExactly(readOnlyDb)

    // Open and close a read-write database
    registry.notifyDatabaseOpenAndClose(readWriteDb)

    assertThat(readOnlyDb.isOpen()).isFalse()
    //    // simulate hook called when readWriteDb was closed
    //    registry.notifyAllDatabaseReferencesReleased(readOnlyDb)
    assertThat(readWriteDb.isOpen()).isTrue()
    assertThat(registry.getConnection(id)).isEqualTo(readWriteDb)
    assertThat(registry.getDatabases(id)).containsExactly(readWriteDb)
  }

  @Test
  fun notifyDatabaseOpened_sendsEventsWhenDatabaseChanges() {
    val path = "${temporaryFolder.root}/db"
    val databaseProvider1 = databaseType.getDatabaseProvider(path, closeablesRule)
    val databaseProvider2 = databaseType.getDatabaseProvider(path, closeablesRule)
    val databaseProvider3 = databaseType.getDatabaseProvider(path, closeablesRule)
    val readOnlyDb = databaseProvider1.getReadOnlyDb(autoClose = false)
    val readWriteDb1 = databaseProvider2.getReadWriteDb(autoClose = false)
    val readWriteDb2 = databaseProvider3.getReadWriteDb(autoClose = false)
    val registry = databaseRegistry(events)

    // Transition from no db to a read-only db
    registry.notifyDatabaseOpened(readOnlyDb)
    assertThat(events).containsExactly(DbOpenedEvent(1, path, isReadOnly = true, databaseType.apiClassName))
    events.clear()

    // Transition from read-only db to writable db
    registry.notifyDatabaseOpened(readWriteDb1)
    assertThat(events).containsExactly(DbOpenedEvent(1, path, isReadOnly = false, databaseType.apiClassName))
    events.clear()

    // Opening another writeable db does not trigger an event
    registry.notifyDatabaseOpened(readWriteDb2)
    assertThat(events).isEmpty()

    // Closing the first writeable db does not trigger an event because we still have another one
    readWriteDb1.close()
    registry.notifyAllDatabaseReferencesReleased(readWriteDb1)
    assertThat(events).isEmpty()

    // Closing the second writeable db does results in a transition to read-only
    readWriteDb2.close()
    registry.notifyAllDatabaseReferencesReleased(readWriteDb2)
    assertThat(events).containsExactly(DbOpenedEvent(1, path, isReadOnly = true, databaseType.apiClassName))
    events.clear()

    // Closing the read-only db triggers a `close` event.
    readOnlyDb.close()
    registry.notifyAllDatabaseReferencesReleased(readOnlyDb)
    assertThat(events).containsExactly(DbClosedEvent(1, path))
  }

  private class DbOpenedCallback(private val events: MutableList<DbEvent>) : OnDatabaseOpenedCallback {

    override fun onDatabaseOpened(databaseId: Int, path: String, isForced: Boolean, isReadOnly: Boolean, apiClassName: String) {
      events.add(DbOpenedEvent(databaseId, path, isReadOnly, apiClassName))
    }
  }

  private class DbClosedCallback(private val events: MutableList<DbEvent>) : OnDatabaseClosedCallback {

    override fun onDatabaseClosed(databaseId: Int, path: String) {
      events.add(DbClosedEvent(databaseId, path))
    }
  }

  private sealed class DbEvent(open val id: Int, open val path: String) {
    data class DbOpenedEvent(override val id: Int, override val path: String, val isReadOnly: Boolean, val apiClassName: String) :
      DbEvent(id, path)

    data class DbClosedEvent(override val id: Int, override val path: String) : DbEvent(id, path)
  }

  private fun databaseRegistry(events: MutableList<DbEvent>, forceOpen: Boolean = false) =
    DatabaseRegistry(DbOpenedCallback(events), DbClosedCallback(events), testMode = true).apply {
      if (forceOpen) {
        enableForceOpen()
      }
      closeablesRule.register(AutoCloseable { dispose() })
    }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "DatabaseType: {0}")
    // parameters are provided as arrays, allowing more than one parameter
    fun params() = listOf(arrayOf(DatabaseType.FRAMEWORK), arrayOf(ANDROID_X))
  }
}

private fun DatabaseRegistry.notifyDatabaseOpenAndClose(db: Database) {
  notifyDatabaseOpened(db)
  notifyReleaseReference(db)
  // call close() only after calling notifyReleaseReference() so the registry can secure a reference
  db.close()
}
