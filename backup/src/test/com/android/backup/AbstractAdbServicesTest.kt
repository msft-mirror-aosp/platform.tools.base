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
package com.android.backup

import com.android.backup.ErrorCode.GMSCORE_IS_TOO_OLD
import com.android.backup.ErrorCode.GMSCORE_NOT_FOUND
import com.android.backup.ErrorCode.PLAY_STORE_NOT_INSTALLED
import com.android.backup.ErrorCode.UNEXPECTED_ERROR
import com.android.backup.testing.FakeAdbServices
import com.android.backup.testing.FakeAdbServices.CommandOverride.Output
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

private const val DUMPSYS_GMSCORE_CMD = "dumpsys package com.google.android.gms"

private const val LAUNCH_COMMAND = "am start market://details?id=com.google.android.gms"
private const val DUMPSYS_ACTIVITY = "dumpsys activity activities"
const val DUMPSYS_PACKAGE = "dumpsys package com.app"
private const val GET_CURRENT_USER = "am get-current-user"
private const val LAUNCH_COMMAND_STDOUT_VALID =
  "Starting: Intent { act=android.intent.action.VIEW dat=market://details/... }"
private const val LAUNCH_COMMAND_STDERR_MISSING_STORE =
  "Error: Activity not started, unable to resolve Intent"

private val DUMPSYS_ACTIVITY_VALID_1 =
  """
    ACTIVITY MANAGER SETTINGS (dumpsys activity settings) activity_manager_constants:
    ...
      mFocusedApp=ActivityRecord{b47d1f u0 com.app/.MainActivity t224}
    ...
  """
    .trimIndent()

private val DUMPSYS_ACTIVITY_VALID_2 =
  """
    ACTIVITY MANAGER SETTINGS (dumpsys activity settings) activity_manager_constants:
    ...
      ResumedActivity: ActivityRecord{cb4266b u0 com.app/.MainActivity} t8}
    ...
  """
    .trimIndent()

val DUMPSYS_PACKAGE_OUT =
  """
    ...
    Packages:
      Package [com.app] (f513cb9):
        ...
        pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA TEST_ONLY ALLOW_BACKUP ]
        User 0: ...
          ...
          runtime permissions:
            permission1: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
            permission2: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
            permission3: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED|ONE_TIME]
        User 10: ...
          runtime permissions:
            permission1: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
            permission2: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
            permission3: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]

    Queries:
  """
    .trimIndent()

/** Tests for [AbstractAdbServices] */
class AbstractAdbServicesTest {

  private val transport = "com.google.android.gms/.backup.migrate.service.D2dTransport"

  @Test
  fun setTransport_withBadExistingTransport_doesNotThrow() = runBlocking {
    val invalidTransport = "Invalid"
    val backupServices = FakeAdbServices("serial", totalSteps = 10)
    backupServices.activeTransport = invalidTransport
    backupServices.withSetup(transport) {
      assertThat(backupServices.activeTransport).isEqualTo(transport)
    }
    assertThat(backupServices.activeTransport).isEqualTo(invalidTransport)
  }

  @Test
  fun missingGmsCore() {
    val backupServices = FakeAdbServices("serial", 10)
    backupServices.addCommandOverride(
      Output(
        DUMPSYS_GMSCORE_CMD,
        """
          If GmsCore is not installed, there will be no line matching "^packages:$"
        """
          .trimIndent(),
      )
    )
    val exception =
      assertThrows(BackupException::class.java) {
        runBlocking { backupServices.withSetup(transport) {} }
      }
    assertThat(exception.errorCode).isEqualTo(GMSCORE_NOT_FOUND)
  }

  @Test
  fun oldGmsCore() {
    val backupServices = FakeAdbServices("serial", 10)
    backupServices.addCommandOverride(
      Output(
        DUMPSYS_GMSCORE_CMD,
        """
          Packages:
              versionCode=50 minSdk=31 targetSdk=34
        """
          .trimIndent(),
      )
    )
    val exception =
      assertThrows(BackupException::class.java) {
        runBlocking { backupServices.withSetup(transport) {} }
      }
    assertThat(exception.errorCode).isEqualTo(GMSCORE_IS_TOO_OLD)
  }

  @Test
  fun sendUpdateGmsIntent_success(): Unit = runBlocking {
    val adbServices = FakeAdbServices("serial", 10)
    adbServices.sendUpdateGmsIntent()
  }

  @Test
  fun sendUpdateGmsIntent_unexpectedStdout() {
    val adbServices = FakeAdbServices("serial", 10)
    adbServices.addCommandOverride(Output(LAUNCH_COMMAND, "unexpected"))

    val exception =
      assertThrows(BackupException::class.java) {
        runBlocking { adbServices.sendUpdateGmsIntent() }
      }
    assertThat(exception.errorCode).isEqualTo(UNEXPECTED_ERROR)
  }

  @Test
  fun sendUpdateGmsIntent_errorInStderr(): Unit = runBlocking {
    val adbServices = FakeAdbServices("serial", 10)
    adbServices.addCommandOverride(Output(LAUNCH_COMMAND, LAUNCH_COMMAND_STDOUT_VALID, "Warning"))

    adbServices.sendUpdateGmsIntent()
  }

  @Test
  fun sendUpdateGmsIntent_warningInStderr() {
    val adbServices = FakeAdbServices("serial", 10)
    adbServices.addCommandOverride(Output(LAUNCH_COMMAND, LAUNCH_COMMAND_STDOUT_VALID, "Error"))

    val exception =
      assertThrows(BackupException::class.java) {
        runBlocking { adbServices.sendUpdateGmsIntent() }
      }
    assertThat(exception.errorCode).isEqualTo(UNEXPECTED_ERROR)
  }

  @Test
  fun sendUpdateGmsIntent_missingPlayStore() {
    val adbServices = FakeAdbServices("serial", 10)
    adbServices.addCommandOverride(
      Output(LAUNCH_COMMAND, LAUNCH_COMMAND_STDOUT_VALID, LAUNCH_COMMAND_STDERR_MISSING_STORE)
    )

    val exception =
      assertThrows(BackupException::class.java) {
        runBlocking { adbServices.sendUpdateGmsIntent() }
      }
    assertThat(exception.errorCode).isEqualTo(PLAY_STORE_NOT_INSTALLED)
  }

  @Test
  fun getForegroundApplicationId_valid1() = runBlocking {
    val adbServices =
      FakeAdbServices("serial", 10)
        .addCommandOverride(Output(DUMPSYS_ACTIVITY, DUMPSYS_ACTIVITY_VALID_1))

    val applicationId = adbServices.getForegroundApplicationId()

    assertThat(applicationId).isEqualTo("com.app")
  }

  @Test
  fun getForegroundApplicationId_valid2() = runBlocking {
    val adbServices =
      FakeAdbServices("serial", 10)
        .addCommandOverride(Output(DUMPSYS_ACTIVITY, DUMPSYS_ACTIVITY_VALID_2))

    val applicationId = adbServices.getForegroundApplicationId()

    assertThat(applicationId).isEqualTo("com.app")
  }

  @Test
  fun getAppInfo_installed() = runBlocking {
    val adbServices = FakeAdbServices("serial", 10)

    assertThat(adbServices.getAppInfo("com.app", withPermissions = false))
      .isEqualTo(AppInfo(debuggable = true, backupEnabled = true, grantedPermissions = emptyList()))
  }

  @Test
  fun getAppInfo_notDebuggable() = runBlocking {
    val adbServices =
      FakeAdbServices("serial", 10)
        .addCommandOverride(
          Output(
            DUMPSYS_PACKAGE,
            "pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA TEST_ONLY ALLOW_BACKUP ]",
          )
        )

    assertThat(adbServices.getAppInfo("com.app", withPermissions = false))
      .isEqualTo(
        AppInfo(debuggable = false, backupEnabled = true, grantedPermissions = emptyList())
      )
  }

  @Test
  fun getAppInfo_backupDisabled() = runBlocking {
    val adbServices =
      FakeAdbServices("serial", 10)
        .addCommandOverride(
          Output(
            DUMPSYS_PACKAGE,
            "pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA TEST_ONLY ]",
          )
        )

    assertThat(adbServices.getAppInfo("com.app", withPermissions = false))
      .isEqualTo(
        AppInfo(debuggable = true, backupEnabled = false, grantedPermissions = emptyList())
      )
  }

  @Test
  fun getAppInfo_not_installed() = runBlocking {
    val adbServices =
      FakeAdbServices("serial", 10).addCommandOverride(Output(DUMPSYS_PACKAGE, "not installed"))

    assertThat(adbServices.getAppInfo("com.app", withPermissions = true)).isNull()
  }

  @Test
  fun getAppInfo_grantedPermissions_user0(): Unit = runBlocking {
    val adbServices =
      FakeAdbServices("serial", 10)
        .addCommandOverride(Output(GET_CURRENT_USER, "0"))
        .addCommandOverride(Output(DUMPSYS_PACKAGE, DUMPSYS_PACKAGE_OUT))

    val permissions = adbServices.getAppInfo("com.app", withPermissions = true)?.grantedPermissions

    assertThat(permissions).containsExactly("permission2")
  }

  @Test
  fun getAppInfo_grantedPermissions_user10(): Unit = runBlocking {
    val adbServices =
      FakeAdbServices("serial", 10)
        .addCommandOverride(Output(GET_CURRENT_USER, "10"))
        .addCommandOverride(Output(DUMPSYS_PACKAGE, DUMPSYS_PACKAGE_OUT))

    val permissions = adbServices.getAppInfo("com.app", withPermissions = true)?.grantedPermissions

    assertThat(permissions).containsExactly("permission1", "permission3")
  }

  @Test
  fun isPlayStoreInstalled_installed() = runBlocking {
    val adbServices = FakeAdbServices("serial", 10)

    assertThat(adbServices.isPlayStoreInstalled()).isTrue()
  }

  @Test
  fun isPlayStoreInstalled_not_installed() = runBlocking {
    val adbServices = FakeAdbServices("serial", 10)
    adbServices.addCommandOverride(
      Output("pm resolve-activity market://details?id=com.android.vending", "No activity found\n")
    )

    assertThat(adbServices.isPlayStoreInstalled()).isFalse()
  }
}
