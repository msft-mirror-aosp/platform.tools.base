/*
 * Copyright 2025 The Android Open Source Project
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
import android.os.Handler
import android.os.Looper
import androidx.inspection.Connection
import androidx.inspection.Inspector
import androidx.inspection.InspectorEnvironment
import androidx.inspection.InspectorExecutors
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.inspection.SqliteInspectorProtocol.AdditionalDriver
import androidx.sqlite.inspection.SqliteInspectorProtocol.Command
import androidx.sqlite.inspection.SqliteInspectorProtocol.Response
import androidx.sqlite.inspection.SqliteInspectorProtocol.TrackDatabasesCommand
import androidx.sqlite.inspection.SqliteInspectorProtocol.TrackDatabasesResponse
import com.android.tools.appinspection.database.testing.FakeArtTooling
import com.android.tools.appinspection.database.testing.TestInspectorFactory
import com.android.tools.appinspection.database.testing.getLogLines
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(
  manifest = Config.NONE,
  minSdk = Build.VERSION_CODES.O,
  maxSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteInspectorTest {
  private val connection = object : Connection() {}
  private val callback = CommandCallback()
  private val environment = FakeInspectorEnvironment()

  @Test
  fun noAndroidX(): Unit = runBlocking {
    val inspector = TestInspectorFactory(coroutineContext).createInspector(connection, environment)
    environment
      .artTooling()
      .registerInvalidClasses("androidx.sqlite.driver.bundled.BundledSQLiteDriver")

    inspector.onReceiveCommand(trackDatabasesCommand(), callback)

    assertThat(callback.responses).containsExactly(trackDatabasesResponse())
  }

  @Test
  fun withAndroidXDrivers_validClasses(): Unit = runBlocking {
    val inspector = TestInspectorFactory(coroutineContext).createInspector(connection, environment)

    inspector.onReceiveCommand(
      trackDatabasesCommand(
        "com.android.tools.appinspection.database.ValidDriver",
        "com.android.tools.appinspection.database.ValidConnection",
      ),
      callback,
    )

    assertThat(callback.responses)
      .containsExactly(
        trackDatabasesResponse(
          "com.android.tools.appinspection.database.ValidDriver",
          "com.android.tools.appinspection.database.ValidConnection",
        )
      )

    assertThat(getLogLines()).isEmpty()
  }

  @Test
  fun withAndroidXDrivers_implicitConnection(): Unit = runBlocking {
    val inspector = TestInspectorFactory(coroutineContext).createInspector(connection, environment)

    inspector.onReceiveCommand(
      trackDatabasesCommand("com.android.tools.appinspection.database.ValidDriver", ""),
      callback,
    )

    assertThat(callback.responses)
      .containsExactly(
        trackDatabasesResponse(
          "com.android.tools.appinspection.database.ValidDriver",
          "com.android.tools.appinspection.database.ValidConnection",
        )
      )

    assertThat(getLogLines()).isEmpty()
  }

  @Test
  fun withAndroidXDrivers_invalidDriver_noClass(): Unit = runBlocking {
    val inspector = TestInspectorFactory(coroutineContext).createInspector(connection, environment)

    inspector.onReceiveCommand(
      trackDatabasesCommand(
        "InvalidDriver",
        "com.android.tools.appinspection.database.ValidConnection",
      ),
      callback,
    )

    assertThat(callback.responses).containsExactly(trackDatabasesResponse())
    assertThat(getLogLines())
      .containsExactly(
        "WARN: SqliteInspector: Can't load class 'InvalidDriver' (ClassNotFoundException)"
      )
  }

  @Test
  fun withAndroidXDrivers_invalidConnection_noClass(): Unit = runBlocking {
    val inspector = TestInspectorFactory(coroutineContext).createInspector(connection, environment)

    inspector.onReceiveCommand(
      trackDatabasesCommand(
        "com.android.tools.appinspection.database.ValidDriver",
        "invalidConnection",
      ),
      callback,
    )

    assertThat(callback.responses).containsExactly(trackDatabasesResponse())
    assertThat(getLogLines())
      .containsExactly(
        "WARN: SqliteInspector: Can't load class 'invalidConnection' (ClassNotFoundException)"
      )
  }

  @Test
  fun withAndroidXDrivers_invalidDriver_notDriver(): Unit = runBlocking {
    val inspector = TestInspectorFactory(coroutineContext).createInspector(connection, environment)

    inspector.onReceiveCommand(
      trackDatabasesCommand(
        "java.lang.String",
        "com.android.tools.appinspection.database.ValidConnection",
      ),
      callback,
    )

    assertThat(callback.responses).containsExactly(trackDatabasesResponse())
    assertThat(getLogLines())
      .containsExactly(
        "WARN: SqliteInspector: Can't load class 'java.lang.String' (ClassCastException)"
      )
  }

  @Test
  fun withAndroidXDrivers_invalidConnection_notDriver(): Unit = runBlocking {
    val inspector = TestInspectorFactory(coroutineContext).createInspector(connection, environment)

    inspector.onReceiveCommand(
      trackDatabasesCommand(
        "com.android.tools.appinspection.database.ValidDriver",
        "java.lang.String",
      ),
      callback,
    )

    assertThat(callback.responses).containsExactly(trackDatabasesResponse())
    assertThat(getLogLines())
      .containsExactly(
        "WARN: SqliteInspector: Can't load class 'java.lang.String' (ClassCastException)"
      )
  }

  class CommandCallback : Inspector.CommandCallback {
    val responses = mutableListOf<Response>()

    override fun reply(response: ByteArray) {
      responses.add(Response.parseFrom(response))
    }

    override fun addCancellationListener(executor: Executor, runnable: Runnable) {}
  }

  private class FakeInspectorEnvironment : InspectorEnvironment {
    private val artTooling = FakeArtTooling()

    override fun artTooling() = artTooling

    override fun executors() =
      object : InspectorExecutors {
        override fun handler(): Handler {
          return Handler(Looper.getMainLooper())
        }

        override fun primary() = directExecutor()

        override fun io() = directExecutor()
      }
  }

  private fun trackDatabasesCommand(
    driverClassName: String? = null,
    connectionClassName: String? = null,
  ): ByteArray {
    val builder = TrackDatabasesCommand.newBuilder()
    if (driverClassName != null) {
      builder.addAdditionalDrivers(
        AdditionalDriver.newBuilder()
          .setDriverClass(driverClassName)
          .setConnectionClass(connectionClassName ?: "")
      )
    }
    return Command.newBuilder().setTrackDatabases(builder).build().toByteArray()
  }
}

@Suppress("unused")
private class ValidDriver : SQLiteDriver {

  override fun open(fileName: String): SQLiteConnection {
    return ValidConnection()
  }
}

private class ValidConnection : SQLiteConnection {

  override fun close() {}

  override fun prepare(sql: String): SQLiteStatement {
    throw NotImplementedError()
  }
}

private fun trackDatabasesResponse(
  driverClassName: String? = null,
  connectionClassName: String? = null,
): Response? {
  val builder = TrackDatabasesResponse.newBuilder()
  if (driverClassName != null && connectionClassName != null) {
    builder.addTrackedAdditionalDrivers(
      AdditionalDriver.newBuilder()
        .setDriverClass(driverClassName)
        .setConnectionClass(connectionClassName)
    )
  }
  return Response.newBuilder().setTrackDatabases(builder).build()
}
