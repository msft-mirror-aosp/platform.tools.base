/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.tools.appinspection.database.androidx

import android.os.Build
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.android.tools.appinspection.common.testing.LogPrinterRule
import com.android.tools.appinspection.database.testing.getLogLines
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(
  manifest = Config.NONE,
  minSdk = Build.VERSION_CODES.O,
  maxSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
)
class SQLiteStatementWrapperTest {
  @get:Rule val logPrinterRule = LogPrinterRule()

  private val executor = Executors.newSingleThreadExecutor()
  private val connection = BundledSQLiteDriver().open(":memory:")

  @Before
  fun setUp() {
    connection.prepare("CREATE TABLE t1 (id INTEGER PRIMARY KEY, name TEXT, score REAL, data BLOB)").use { it.step() }
  }

  @After
  fun tearDown() {
    connection.close()
    executor.shutdownNow()
  }

  @Test
  fun uncachedStatement_locksOnPrepareAndUnlocksOnClose() {
    var invalidations = 0
    val statement = prepareWrapped(connection, "INSERT INTO t1 (id, name) VALUES (1, 'a')") { invalidations++ }

    statement.assertConnectionLockedByCurrentThread()

    statement.step()
    assertThat(invalidations).isEqualTo(1)
    statement.assertConnectionLockedByCurrentThread()

    statement.close()
    statement.assertConnectionUnlocked()
    assertThat(getLogLines()).isEmpty()
  }

  @Test
  fun cachedStatement_unlocksOnResetAndRelocksOnReuse() {
    var invalidations = 0
    val statement = prepareWrapped(connection, "INSERT INTO t1 (id, name) VALUES (?, ?)") { invalidations++ }

    // Simulate Room's CachedStatement wrapper where close() calls reset() and clearBindings()
    val cachedStatement =
      object : SQLiteStatement by statement {
        override fun close() {
          statement.reset()
          statement.clearBindings()
        }
      }

    // 1. First execution (Cache Miss)
    statement.assertConnectionLockedByCurrentThread()
    cachedStatement.use {
      it.bindInt(1, 1)
      it.bindText(2, "first")
      it.step()
    }
    assertThat(invalidations).isEqualTo(1)

    // After CachedStatement.close() (which called reset() + clearBindings()), lock must be released
    statement.assertConnectionUnlocked()

    // 2. Second execution (Cache Hit - prepare() is not called again)
    cachedStatement.use {
      it.bindInt(1, 2)
      statement.assertConnectionLockedByCurrentThread()
      it.bindText(2, "second")
      it.step()
    }
    assertThat(invalidations).isEqualTo(2)
    statement.assertConnectionUnlocked()

    // 3. Eviction from cache calls wrapped.close() while already unlocked
    statement.close()
    statement.assertConnectionUnlocked()
    assertThat(getLogLines()).isEmpty()
  }

  @Test
  fun clearBindings_isLockNeutral() {
    val statement = prepareWrapped(connection, "INSERT INTO t1 (id, name) VALUES (?, ?)")

    // When locked, clearBindings() keeps the statement locked
    statement.assertConnectionLockedByCurrentThread()
    statement.bindInt(1, 1)
    statement.clearBindings()
    statement.assertConnectionLockedByCurrentThread()

    // When unlocked (after reset()), clearBindings() keeps the statement unlocked
    statement.reset()
    statement.assertConnectionUnlocked()
    statement.clearBindings()
    statement.assertConnectionUnlocked()

    statement.close()
    assertThat(getLogLines()).isEmpty()
  }

  @Test
  fun timedOutLock_skipsRepeatedRetriesAndReleasesCleanlyWithoutCrash() {
    val connectionLocking = ConnectionLocking(500.milliseconds)

    val statement = prepareWrapped(connection, "INSERT INTO t1 (id, name) VALUES (?, ?)", connectionLocking)
    statement.reset()
    statement.assertConnectionUnlocked()

    // Simulate two nested leaked statements on another thread (holdCount = 2)
    executor
      .submit {
        connectionLocking.acquireLock(connection)
        connectionLocking.acquireLock(connection)
      }
      .get(3, SECONDS)

    val startMs = System.currentTimeMillis()

    // First operation on cached statement times out (unlocking 1 of the 2 leaked holds -> holdCount
    // = 1)
    statement.bindInt(1, 1)
    assertThat(getLogLines()).containsExactly("ERROR: SqliteInspector: Failed to acquire a lock on the database connection. (Exception)")
    ShadowLog.reset()

    // Subsequent operations in the same cycle should NOT retry acquireLock (no extra 500ms timeouts)
    statement.bindText(2, "timed_out_cycle")
    statement.step()
    val elapsedMs = System.currentTimeMillis() - startMs
    assertThat(elapsedMs).isLessThan(1200)

    // reset() should NOT call releaseLock when lockState is TIMED_OUT, and resets state to UNLOCKED
    statement.reset()

    // Release the remaining leaked hold on the other thread
    executor.submit { connectionLocking.releaseLock(connection) }.get(3, SECONDS)

    // Next reuse of the cached statement should acquire and release the lock normally
    statement.bindInt(1, 2)
    statement.assertConnectionLockedByCurrentThread()
    statement.bindText(2, "recovered_cycle")
    statement.step()
    statement.reset()
    statement.assertConnectionUnlocked()
    assertThat(getLogLines()).isEmpty()
    statement.close()
  }

  @Test
  fun lockingFunctions() {
    testLockingFunction { it.bindBlob(1, ByteArray(0)) }
    testLockingFunction { it.bindDouble(1, 0.0) }
    testLockingFunction { it.bindFloat(1, 0f) }
    testLockingFunction { it.bindLong(1, 0L) }
    testLockingFunction { it.bindInt(1, 0) }
    testLockingFunction { it.bindBoolean(1, true) }
    testLockingFunction { it.bindText(1, "") }
    testLockingFunction { it.bindNull(1) }
    testLockingFunction { it.getBlob(1) }
    testLockingFunction { it.getDouble(1) }
    testLockingFunction { it.getFloat(1) }
    testLockingFunction { it.getLong(1) }
    testLockingFunction { it.getInt(1) }
    testLockingFunction { it.getBoolean(1) }
    testLockingFunction { it.getText(1) }
    testLockingFunction { it.isNull(1) }
    testLockingFunction { it.getColumnCount() }
    testLockingFunction { it.getColumnName(1) }
    testLockingFunction { it.getColumnNames() }
    testLockingFunction { it.getColumnType(1) }
    testLockingFunction { it.step() }
  }

  @Test
  fun unlockingFunctions() {
    testUnlockingFunction { it.close() }
    testUnlockingFunction { it.reset() }
  }

  @Test
  fun neutralFunctions() {
    testNeutralFunction { it.clearBindings() }
  }

  private fun testLockingFunction(block: (SQLiteStatement) -> Unit) {
    val connectionLocking = mock<ConnectionLocking>()
    val wrapper = SQLiteStatementWrapper(connectionLocking, connection, mock())
    wrapper.close() // force unlock

    block(wrapper)

    verify(connectionLocking).acquireLock(connection)
  }

  private fun testUnlockingFunction(block: (SQLiteStatement) -> Unit) {
    val connectionLocking = mock<ConnectionLocking>()
    val wrapper = SQLiteStatementWrapper(connectionLocking, connection, mock())

    block(wrapper)

    verify(connectionLocking).releaseLock(connection)
  }

  private fun testNeutralFunction(block: (SQLiteStatement) -> Unit) {
    val connectionLocking = mock<ConnectionLocking>()
    val wrapper = SQLiteStatementWrapper(connectionLocking, connection, mock())

    block(wrapper) // when locked
    wrapper.close() // force unlock
    block(wrapper) // when unlocked

    verify(connectionLocking).releaseLock(connection)
    verify(connectionLocking, never()).acquireLock(connection)
  }

  private fun prepareWrapped(
    connection: SQLiteConnection,
    sql: String,
    connectionLocking: ConnectionLocking = ConnectionLocking(),
    onInvalidate: () -> Unit = {},
  ): SQLiteStatementWrapper {
    val isLocked = connectionLocking.acquireLock(connection)
    return SQLiteStatementWrapper(
      connectionLocking,
      connection,
      connection.prepare(sql),
      isLocked,
      onInvalidate,
    )
  }

  private fun SQLiteStatementWrapper.assertConnectionUnlocked() {
    val acquiredOnOtherThread = AtomicInteger(0)
    executor
      .submit {
        connectionLocking.acquireLock(connection)
        acquiredOnOtherThread.incrementAndGet()
        connectionLocking.releaseLock(connection)
      }
      .get(1, SECONDS)
    assertThat(acquiredOnOtherThread.get()).isEqualTo(1)
  }

  private fun SQLiteStatementWrapper.assertConnectionLockedByCurrentThread() {
    val entered = CountDownLatch(1)
    val finished = AtomicBoolean(false)
    val future = executor.submit {
      entered.countDown()
      connectionLocking.acquireLock(connection)
      finished.set(true)
      connectionLocking.releaseLock(connection)
    }
    assertThat(entered.await(1, SECONDS)).isTrue()
    Thread.sleep(20)
    assertThat(finished.get()).isFalse()

    connectionLocking.releaseLock(connection)
    future.get(1, SECONDS)
    assertThat(finished.get()).isTrue()
    connectionLocking.acquireLock(connection)
  }
}
