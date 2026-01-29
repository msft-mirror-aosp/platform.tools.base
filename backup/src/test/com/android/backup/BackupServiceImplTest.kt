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

import com.android.backup.BackupResult.Error
import com.android.backup.BackupResult.Success
import com.android.backup.BackupResult.WithoutAppData
import com.android.backup.BackupType.CLOUD
import com.android.backup.BackupType.CLOUD_UNENCRYPTED
import com.android.backup.BackupType.DEVICE_TO_DEVICE
import com.android.backup.ErrorCode.APP_NOT_DEBUGGABLE
import com.android.backup.ErrorCode.APP_NOT_INSTALLED
import com.android.backup.ErrorCode.APP_STOPPED
import com.android.backup.ErrorCode.BACKUP_MANAGER_IS_NOT_RUNNING
import com.android.backup.ErrorCode.BACKUP_NOT_ACTIVATED
import com.android.backup.ErrorCode.BACKUP_NOT_ENABLED
import com.android.backup.ErrorCode.BACKUP_NOT_SUPPORTED
import com.android.backup.ErrorCode.BMGR_ERROR_BACKUP
import com.android.backup.ErrorCode.BMGR_ERROR_RESTORE
import com.android.backup.ErrorCode.CANNOT_ENABLE_BMGR
import com.android.backup.ErrorCode.GMSCORE_NOT_FOUND
import com.android.backup.ErrorCode.INVALID_BACKUP_FILE
import com.android.backup.ErrorCode.TRANSPORT_INIT_FAILED
import com.android.backup.ErrorCode.TRANSPORT_NOT_SELECTED
import com.android.backup.testing.BackupFileHelper
import com.android.backup.testing.BackupFileHelper.FileInfo
import com.android.backup.testing.FakeAdbServices.CommandOverride.Output
import com.android.backup.testing.FakeAdbServices.CommandOverride.Throw
import com.android.backup.testing.FakeAdbServicesFactory
import com.android.backup.testing.asBackupResult
import com.google.common.truth.Truth.assertThat
import com.jetbrains.rd.generator.nova.fail
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.pathString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupServiceImplTest {

  @get:Rule val temporaryFolder = TemporaryFolder()
  private val backupFileHelper = BackupFileHelper(temporaryFolder)

  @Test
  fun backup_d2d(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory(("com.app"))
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "am get-current-user",
        "dumpsys package com.app",
        "dumpsys backup users",
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr list transports",
        "bmgr transport -c com.google.android.gms/.backup.component.D2dTransportService",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "settings put secure backup_testing_flows_type 0",
        "bmgr backupnow @pm@ com.app --non-incremental --monitor-verbose",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
    val files = backupFile.unzip()
    assertThat(files.keys).containsExactly("pm_backup", "restore_token_file", "app_backup", "auth_backup", "permissions", "metadata.txt")
    assertThat(adbServices.testMode).isEqualTo(0)
    assertThat(backupFile.exists()).isTrue()
    assertThat(files["pm_backup"]).isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/pm_backup")
    assertThat(files["restore_token_file"])
      .isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/restore_token_file")
    assertThat(files["app_backup"]).isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/app_backup")
    val metadata = BackupService.getMetadata(backupFile)
    assertThat(metadata).isEqualTo(BackupMetadata("com.app", DEVICE_TO_DEVICE))
  }

  @Test
  fun backup_cloud(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory("com.app")
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.backup("serial", "com.app", CLOUD, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "am get-current-user",
        "dumpsys package com.app",
        "dumpsys backup users",
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr list transports",
        "bmgr transport -c com.google.android.gms/.backup.component.D2dTransportService",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "settings put secure backup_testing_flows_type 1",
        "bmgr backupnow @pm@ com.app --non-incremental --monitor-verbose",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
    assertThat(adbServices.testMode).isEqualTo(0)
    assertThat(backupFile.exists()).isTrue()
    val files = backupFile.unzip()
    assertThat(files.keys).containsExactly("pm_backup", "restore_token_file", "app_backup", "auth_backup", "permissions", "metadata.txt")
    assertThat(files["pm_backup"]).isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/pm_backup")
    assertThat(files["restore_token_file"])
      .isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/restore_token_file")
    assertThat(files["app_backup"]).isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/app_backup")
    val metadata = BackupService.getMetadata(backupFile)
    assertThat(metadata).isEqualTo(BackupMetadata("com.app", CLOUD))
  }

  @Test
  fun backup_cloudUnencrypted(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory("com.app")
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.backup("serial", "com.app", CLOUD_UNENCRYPTED, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "am get-current-user",
        "dumpsys package com.app",
        "dumpsys backup users",
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr list transports",
        "bmgr transport -c com.google.android.gms/.backup.component.D2dTransportService",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "settings put secure backup_testing_flows_type 2",
        "bmgr backupnow @pm@ com.app --non-incremental --monitor-verbose",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
    assertThat(adbServices.testMode).isEqualTo(0)
    assertThat(backupFile.exists()).isTrue()
    val files = backupFile.unzip()
    assertThat(files.keys).containsExactly("pm_backup", "restore_token_file", "app_backup", "auth_backup", "permissions", "metadata.txt")
    assertThat(files["pm_backup"]).isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/pm_backup")
    assertThat(files["restore_token_file"])
      .isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/restore_token_file")
    assertThat(files["app_backup"]).isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/app_backup")
    val metadata = BackupService.getMetadata(backupFile)
    assertThat(metadata).isEqualTo(BackupMetadata("com.app", CLOUD_UNENCRYPTED))
  }

  @Test
  fun backup_permissions(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory("com.app") { it.addCommandOverride(Output(DUMPSYS_PACKAGE, DUMPSYS_PACKAGE_OUT)) }
    val backupService = BackupServiceImpl(adbServicesFactory)

    backupService.backup("serial", "com.app", CLOUD_UNENCRYPTED, backupFile, null)

    assertThat(backupFile.exists()).isTrue()
    val files = backupFile.unzip()
    assertThat(files["permissions"]?.lines()).containsExactly("permission2")
  }

  @Test
  fun backup_backupDisabled_withAuth(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory =
      FakeAdbServicesFactory("com.app") {
        it.addCommandOverride(Output("dumpsys package com.app", "pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA TEST_ONLY ]"))
        it.addContentOverride("content://com.google.android.gms.fileprovider/backup_testing_flows/auth_backup", "valid")
      }
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(result).isEqualTo(WithoutAppData)
  }

  @Test
  fun backup_backupDisabled_withoutAuth(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory =
      FakeAdbServicesFactory("com.app") {
        it.addCommandOverride(Output("dumpsys package com.app", "pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA TEST_ONLY ]"))
        it.addContentOverride("content://com.google.android.gms.fileprovider/backup_testing_flows/auth_backup", "")
      }
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    val error = result as Error
    assertThat(error.errorCode).isEqualTo(BACKUP_NOT_ENABLED)
  }

  @Test
  fun backup_bmgrAlreadyEnabled(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory("com.app") { it.bmgrEnabled = true }
    val backupServices = BackupServiceImpl(adbServicesFactory)

    val result = backupServices.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "am get-current-user",
        "dumpsys package com.app",
        "dumpsys backup users",
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr list transports",
        "bmgr transport -c com.google.android.gms/.backup.component.D2dTransportService",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "settings put secure backup_testing_flows_type 0",
        "bmgr backupnow @pm@ com.app --non-incremental --monitor-verbose",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
      )
      .inOrder()
  }

  @Test
  fun backup_appNotInstalled(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory("com.app") { it.addCommandOverride(Output("dumpsys package com.app", "")) }
    val backupServices = BackupServiceImpl(adbServicesFactory)

    val result = backupServices.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    val error = result as Error
    assertThat(error.errorCode).isEqualTo(APP_NOT_INSTALLED)
    assertThat(adbServices.getCommands()).containsExactly("am get-current-user", "dumpsys package com.app")
  }

  @Test
  fun backup_transportAlreadySelected(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory =
      FakeAdbServicesFactory("com.app") { it.activeTransport = "com.google.android.gms/.backup.migrate.service.D2dTransport" }
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "am get-current-user",
        "dumpsys package com.app",
        "dumpsys backup users",
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "settings put secure backup_testing_flows_type 0",
        "bmgr backupnow @pm@ com.app --non-incremental --monitor-verbose",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
  }

  @Test
  fun backup_assertProgress(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory("com.app")
    val backupService = BackupServiceImpl(adbServicesFactory)

    backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(adbServices.getProgress())
      .containsExactly(
        "1/10: Verifying Google services",
        "2/10: Checking if BMGR is enabled",
        "3/12: Enabling BMGR",
        "4/12: Enabling test mode",
        "5/12: Setting backup transport",
        "6/13: Initializing backup transport",
        "7/13: Running backup",
        "8/13: Fetching backup",
        "9/13: Cleaning up",
        "10/13: Restoring backup transport",
        "11/13: Disabling test mode",
        "12/13: Disabling BMGR",
        "13/13: Done",
      )
      .inOrder()
  }

  @Test
  fun backup_doesNotDeleteFileIfFails(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    backupFile.createFile()
    val backupService = BackupServiceImpl(FakeAdbServicesFactory("com.app") { it.transports = emptyList() })

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(result).isNotEqualTo(Success)
    assertThat(backupFile.exists()).isTrue()
  }

  @Test
  fun backup_pullFails_deletesFile(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService = BackupServiceImpl(FakeAdbServicesFactory("com.app") { it.failReadWriteContent = true })

    backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(backupFile.notExists()).isTrue()
  }

  @Test
  fun backup_initTransportFails(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory("com.app") {
          it.addCommandOverride(
            Output("bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport", "Initialization result: -100")
          )
        }
      )

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(result)
      .isEqualTo(
        TRANSPORT_INIT_FAILED.asBackupResult(
          "Failed to initialize 'com.google.android.gms/.backup.migrate.service.D2dTransport`: Initialization result: -100"
        )
      )
  }

  @Test
  fun backup_initTransportMaybeFailsAndBackupSucceeds(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory("com.app") {
          it.addCommandOverride(
            Output("bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport", "Initialization result: -1000")
          )
        }
      )

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(result).isEqualTo(Success)
  }

  @Test
  fun backup_initTransportMaybeFailsAndBackupFails(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory("com.app") {
          it.addCommandOverride(
            Output("bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport", "Initialization result: -1000")
          )
          it.addCommandOverride(
            Output(
              "bmgr backupnow @pm@ com.app --non-incremental --monitor-verbose",
              """
              Running non-incremental backup for 2 requested packages.
              Package @pm@ with result: Success
              => Event{AGENT / FULL_BACKUP_CANCEL : package = com.example.empty(v1)}
              Package com.example.empty with result: ERROR1
              Backup finished with result: ERROR2
              """
                .trimIndent(),
            )
          )
        }
      )

    val error = backupService.backup("serial", "com.app", CLOUD, backupFile, null) as Error

    assertThat(error.errorCode).isEqualTo(TRANSPORT_INIT_FAILED)
    assertThat(error.throwable.message)
      .isEqualTo(
        """
        Failed to backup 'com.app`:
        Backup was cancelled by either the user or backup service lifecycle.
        Backup failed for package: com.example.empty
        Backup operation failed.
        """
          .trimIndent()
      )
  }

  @Test
  fun backup_gmsCoreNotFound(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory("com.app") {
          it.addCommandOverride(Output("dumpsys package com.google.android.gms", "dumpsys package com.google.android.gms"))
        }
      )

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(result).isEqualTo(GMSCORE_NOT_FOUND.asBackupResult("Google Services not found on device"))
  }

  @Test
  fun backup_backupFailed(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory("com.app") {
          it.addCommandOverride(
            Output(
              "bmgr backupnow @pm@ com.app --non-incremental --monitor-verbose",
              """
              Running non-incremental backup for 2 requested packages.
              Package @pm@ with result: Success
              => Event{AGENT / FULL_BACKUP_CANCEL : package = com.example.empty(v1)}
              Package com.example.empty with result: ERROR1
              Backup finished with result: ERROR2
              """
                .trimIndent(),
            )
          )
        }
      )

    val error = backupService.backup("serial", "com.app", CLOUD, backupFile, null) as Error

    assertThat(error.errorCode).isEqualTo(BMGR_ERROR_BACKUP)
    val backupException = error.throwable as BackupException
    assertThat(backupException.message)
      .isEqualTo(
        """
        Failed to backup 'com.app`:
        Backup was cancelled by either the user or backup service lifecycle.
        Backup failed for package: com.example.empty
        Backup operation failed.
        """
          .trimIndent()
      )
    val bmgrException = backupException.cause as BmgrException
    assertThat(bmgrException.message)
      .isEqualTo(
        """
        Command 'bmgr backupnow @pm@ com.app --non-incremental --monitor-verbose' failed with the following output:
        Running non-incremental backup for 2 requested packages.
        Package @pm@ with result: Success
        => Event{AGENT / FULL_BACKUP_CANCEL : package = com.example.empty(v1)}
        Package com.example.empty with result: ERROR1
        Backup finished with result: ERROR2
        """
          .trimIndent()
      )
    assertThat(bmgrException.errorCodes).isEqualTo("FULL_BACKUP_CANCEL ERROR1 ERROR2")
  }

  @Test
  fun backup_appStopped(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory("com.app") {
          it.addCommandOverride(
            Output(
              "bmgr backupnow @pm@ com.app --non-incremental --monitor-verbose",
              "=> Event{BACKUP_MANAGER_POLICY / PACKAGE_STOPPED : package = com.app(v1)}",
            )
          )
        }
      )

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(result)
      .isEqualTo(APP_STOPPED.asBackupResult("Application 'com.app' is in a stopped state. Please launch the app and try again."))
  }

  @Test
  fun backup_createsParentDirectories(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "foo/bar/file.backup")
    val adbServicesFactory = FakeAdbServicesFactory("com.app")
    val backupService = BackupServiceImpl(adbServicesFactory)

    backupService.backup("serial", "com.app", CLOUD, backupFile, null)

    assertThat(backupFile.exists()).isTrue()
  }

  @Test
  fun restore_cloud(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900", CLOUD)
    val adbServicesFactory = FakeAdbServicesFactory("com.app")
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.restore("serial", backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.app",
        "am get-current-user",
        "dumpsys backup users",
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr list transports",
        "bmgr transport -c com.google.android.gms/.backup.component.D2dTransportService",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr transport -c com.google.android.gms/.backup.BackupTransportService",
        "bmgr list transports",
        "pm clear com.app",
        "settings put secure backup_testing_flows_type 1",
        "bmgr restore 9bc1546914997f6c com.app --monitor-verbose",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
    assertThat(adbServices.testMode).isEqualTo(0)
  }

  @Test
  fun restore_d2d(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900", DEVICE_TO_DEVICE)
    val adbServicesFactory = FakeAdbServicesFactory("com.app")
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.restore("serial", backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.app",
        "am get-current-user",
        "dumpsys backup users",
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr list transports",
        "bmgr transport -c com.google.android.gms/.backup.component.D2dTransportService",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr transport -c com.google.android.gms/.backup.BackupTransportService",
        "bmgr list transports",
        "pm clear com.app",
        "settings put secure backup_testing_flows_type 0",
        "bmgr restore 9bc1546914997f6c com.app --monitor-verbose",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
    assertThat(adbServices.testMode).isEqualTo(0)
  }

  @Test
  fun restore_withPermission(): Unit = runBlocking {
    val backupFile =
      backupFileHelper.createBackupFile(
        "com.app",
        "11223344556677889900",
        DEVICE_TO_DEVICE,
        permissions = listOf("permission1", "permission2"),
      )
    val adbServicesFactory = FakeAdbServicesFactory("com.app")
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.restore("serial", backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.app",
        "am get-current-user",
        "dumpsys backup users",
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr list transports",
        "bmgr transport -c com.google.android.gms/.backup.component.D2dTransportService",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr transport -c com.google.android.gms/.backup.BackupTransportService",
        "bmgr list transports",
        "pm clear com.app",
        "settings put secure backup_testing_flows_type 0",
        "bmgr restore 9bc1546914997f6c com.app --monitor-verbose",
        "pm grant com.app permission1",
        "pm grant com.app permission2",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
    assertThat(adbServices.testMode).isEqualTo(0)
  }

  @Test
  fun restore_bmgrAlreadyEnabled(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val adbServicesFactory = FakeAdbServicesFactory("com.app") { it.bmgrEnabled = true }
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.restore("serial", backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    (result as? Error)?.throwable?.printStackTrace()
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.app",
        "am get-current-user",
        "dumpsys backup users",
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr list transports",
        "bmgr transport -c com.google.android.gms/.backup.component.D2dTransportService",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr transport -c com.google.android.gms/.backup.BackupTransportService",
        "bmgr list transports",
        "pm clear com.app",
        "settings put secure backup_testing_flows_type 1",
        "bmgr restore 9bc1546914997f6c com.app --monitor-verbose",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
      )
      .inOrder()
  }

  @Test
  fun restore_transportNotSet(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val adbServicesFactory = FakeAdbServicesFactory("com.app") { it.activeTransport = "com.android.localtransport/.LocalTransport" }
    val backupService = BackupServiceImpl(adbServicesFactory)

    backupService.restore("serial", backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.app",
        "am get-current-user",
        "dumpsys backup users",
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr list transports",
        "bmgr transport -c com.google.android.gms/.backup.component.D2dTransportService",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr transport -c com.google.android.gms/.backup.BackupTransportService",
        "bmgr list transports",
        "pm clear com.app",
        "settings put secure backup_testing_flows_type 1",
        "bmgr restore 9bc1546914997f6c com.app --monitor-verbose",
        "bmgr transport com.android.localtransport/.LocalTransport",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
  }

  @Test
  fun restore_assertProgress(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val adbServicesFactory = FakeAdbServicesFactory("com.app")
    val backupService = BackupServiceImpl(adbServicesFactory)

    backupService.restore("serial", backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(adbServices.getProgress())
      .containsExactly(
        "1/11: Verifying Google services",
        "2/11: Checking if BMGR is enabled",
        "3/13: Enabling BMGR",
        "4/13: Enabling test mode",
        "5/13: Setting backup transport",
        "6/14: Initializing backup transport",
        "7/14: Pushing backup file",
        "8/14: Clearing app data",
        "9/14: Restoring com.app",
        "10/14: Restoring com.app permissions",
        "11/14: Restoring backup transport",
        "12/14: Disabling test mode",
        "13/14: Disabling BMGR",
        "14/14: Done",
      )
      .inOrder()
  }

  @Test
  fun restore_withoutAuth(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900", CLOUD, withAuth = false)
    val adbServicesFactory = FakeAdbServicesFactory("com.app")
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.restore("serial", backupFile, null)

    assertThat(result).isEqualTo(Success)
  }

  @Test
  fun restore_notDebuggable(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900", CLOUD, withAuth = false)
    val adbServicesFactory =
      FakeAdbServicesFactory("com.app") { it.addCommandOverride(Output("dumpsys package com.app", "pkgFlags=[ ALLOW_BACKUP ]")) }
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.restore("serial", backupFile, null)

    val error = result as Error
    assertThat(error.errorCode).isEqualTo(APP_NOT_DEBUGGABLE)
    assertThat(error.throwable.message).isEqualTo("Application 'com.app' is not debuggable")
  }

  @Test
  fun validateBackupFile() {
    BackupService.validateBackupFile(backupFileHelper.createBackupFile("com.app", "11223344556677889900"))
  }

  @Test
  fun validateBackupFile_noApplicationId() {
    val exception =
      assertThrows(BackupException::class.java) {
        BackupService.validateBackupFile(
          backupFileHelper.createZipFile(FileInfo("@pm@", ""), FileInfo("restore_token_file", "11223344556677889900"))
        )
      }

    assertThat(exception.errorCode).isEqualTo(INVALID_BACKUP_FILE)
    assertThat(exception.message).startsWith("Backup file does not contain metadata")
  }

  @Test
  fun validateBackupFile_invalidToken() {
    val exception =
      assertThrows(BackupException::class.java) { BackupService.validateBackupFile(backupFileHelper.createBackupFile("com.app", "foobar")) }

    assertThat(exception.errorCode).isEqualTo(INVALID_BACKUP_FILE)
    assertThat(exception.message).startsWith("Backup file does not contain a valid token")
  }

  @Test
  fun validateBackupFile_notZipFile() {
    val exception =
      assertThrows(BackupException::class.java) {
        val path = Path.of(temporaryFolder.root.path, "file.backup").createFile()
        BackupService.validateBackupFile(path)
      }

    assertThat(exception.errorCode).isEqualTo(INVALID_BACKUP_FILE)
    assertThat(exception.message).startsWith("File is not a valid backup file")
  }

  @Test
  fun validateBackupFile_unexpectedFile() {
    val exception =
      assertThrows(BackupException::class.java) {
        BackupService.validateBackupFile(
          backupFileHelper.createZipFile(
            FileInfo("@pm@", ""),
            FileInfo("restore_token_file", "11223344556677889900"),
            FileInfo("com.app", ""),
            FileInfo("extra file", ""),
          )
        )
      }

    assertThat(exception.errorCode).isEqualTo(INVALID_BACKUP_FILE)
    assertThat(exception.message).startsWith("Backup file does not contain metadata")
  }

  @Test
  fun validateBackupFile_missingPmFile() {
    val exception =
      assertThrows(BackupException::class.java) {
        BackupService.validateBackupFile(
          backupFileHelper.createZipFile(FileInfo("restore_token_file", "11223344556677889900"), FileInfo("com.app", ""))
        )
      }

    assertThat(exception.errorCode).isEqualTo(INVALID_BACKUP_FILE)
    assertThat(exception.message).startsWith("Backup file does not contain metadata")
  }

  @Test
  fun restore_enableBmgrFails(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val backupService = BackupServiceImpl(FakeAdbServicesFactory("com.app") { it.addCommandOverride(Throw("bmgr enabled")) })

    val result = backupService.restore("serial", backupFile, null)

    assertThat(result).isEqualTo(CANNOT_ENABLE_BMGR.asBackupResult())
  }

  @Test
  fun restore_appNotInstalled(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val backupService =
      BackupServiceImpl(FakeAdbServicesFactory("com.app") { it.addCommandOverride(Output("dumpsys package com.app", "")) })

    val result = backupService.restore("serial", backupFile, null)

    assertThat(result).isEqualTo(APP_NOT_INSTALLED.asBackupResult("Application 'com.app' is not installed on the device"))
  }

  @Test
  fun restore_setTransportFails(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val backupService =
      BackupServiceImpl(FakeAdbServicesFactory("com.app") { it.addCommandOverride(Output("bmgr list transports", "unexpected output")) })

    val result = backupService.restore("serial", backupFile, null)

    assertThat(result).isEqualTo(TRANSPORT_NOT_SELECTED.asBackupResult("Requested transport was not set: unexpected output"))
  }

  @Test
  fun restore_invalidBackupFile(): Unit = runBlocking {
    val backupFile = backupFileHelper.createZipFile(FileInfo("some-file", ""))

    val backupService = BackupServiceImpl(FakeAdbServicesFactory("com.app"))

    val error = backupService.restore("serial", backupFile, null) as? Error ?: fail("Expected an Error")

    assertThat(error.errorCode).isEqualTo(INVALID_BACKUP_FILE)
    assertThat(error.throwable.message).startsWith("Backup file does not contain metadata")
  }

  @Test
  fun restore_restoreFailed(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory("com.app") {
          it.addCommandOverride(
            Output(
              "bmgr restore 9bc1546914997f6c com.app --monitor-verbose",
              """
              => Event{BACKUP_MANAGER_POLICY / SIGNATURE_MISMATCH : package = com.app(v1)}
              restoreFinished: -1
              """
                .trimIndent(),
            )
          )
        }
      )

    val error = backupService.restore("serial", backupFile, null) as Error

    assertThat(error.errorCode).isEqualTo(BMGR_ERROR_RESTORE)
    val backupException = error.throwable as BackupException
    assertThat(backupException.message)
      .isEqualTo(
        """
        Failed to restore 'com.app`:
        Signature of the app for which restore is called doesn't match the signature of the app corresponding to the backup.
        Restore operation failed
        """
          .trimIndent()
      )
    val bmgrException = backupException.cause as BmgrException
    assertThat(bmgrException.message)
      .isEqualTo(
        """
        Command 'bmgr restore 9bc1546914997f6c com.app --monitor-verbose' failed with the following output:
        => Event{BACKUP_MANAGER_POLICY / SIGNATURE_MISMATCH : package = com.app(v1)}
        restoreFinished: -1
        """
          .trimIndent()
      )
    assertThat(bmgrException.errorCodes).isEqualTo("SIGNATURE_MISMATCH -1")
  }

  @Test
  fun restore_notSupported(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory("com.app") {
          it.addCommandOverride(
            Output("bmgr enabled", stdout = "", stderr = "Error: Could not access the Backup Manager.  Is the system running?\n")
          )
        }
      )

    val result = backupService.restore("serial", backupFile, null)

    assertThat(result).isEqualTo(BACKUP_NOT_SUPPORTED.asBackupResult("Backup is not supported on this device"))
  }

  @Test
  fun restore_notActivated(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory("com.app") {
          it.addCommandOverride(Output("bmgr enabled", stdout = "", stderr = "Error: Backup Manager is not activated for user 0\n"))
        }
      )

    val result = backupService.restore("serial", backupFile, null)

    assertThat(result).isEqualTo(BACKUP_NOT_ACTIVATED.asBackupResult("Backup is not activated on this device"))
  }

  @Test
  fun isBackupEnabled_enabled(): Unit = runBlocking {
    val adbServicesFactory =
      FakeAdbServicesFactory(("com.app")) { it.addCommandOverride(Output("dumpsys package com.app", "pkgFlags=[ ALLOW_BACKUP ]")) }
    val backupService = BackupServiceImpl(adbServicesFactory)

    assertThat(backupService.isBackupEnabled("serial", "com.app")).isTrue()
  }

  @Test
  fun isBackupEnabled_disabled(): Unit = runBlocking {
    val adbServicesFactory =
      FakeAdbServicesFactory(("com.app")) { it.addCommandOverride(Output("dumpsys package com.app", "pkgFlags=[  ]")) }
    val backupService = BackupServiceImpl(adbServicesFactory)

    assertThat(backupService.isBackupEnabled("serial", "com.app")).isFalse()
  }

  @Test
  fun isBackupEnabled_noFlags(): Unit = runBlocking {
    val adbServicesFactory = FakeAdbServicesFactory(("com.app")) { it.addCommandOverride(Output("dumpsys package com.app", "")) }
    val backupService = BackupServiceImpl(adbServicesFactory)

    assertThat(backupService.isBackupEnabled("serial", "com.app")).isFalse()
  }

  @Test
  fun getDebuggableApps(): Unit = runBlocking {
    val adbServicesFactory =
      FakeAdbServicesFactory(("com.app")) {
        it.addCommandOverride(
          Output(
            "dumpsys package",
            """
            Packages:
              Package [app1] (3c318f1):
                ...
                pkgFlags=[ foo bar ]
                ...
              Package [app2] (3c318f1):
                ...
                pkgFlags=[ foo DEBUGGABLE bar ]
                ...
              Package [app3] (3c318f1):
                ...
                pkgFlags=[ DEBUGGABLE bar ]
                ...
              Package [app4] (3c318f1):
                ...
                pkgFlags=[ foo DEBUGGABLE ]
                ...
            """
              .trimIndent(),
          )
        )
      }
    val backupService = BackupServiceImpl(adbServicesFactory)

    assertThat(backupService.getDebuggableApps("serial")).containsExactly("app2", "app3", "app4")
  }

  @Test
  fun backup_backupManagerNotRunning(): Unit = runTest {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")

    val adbServicesFactory = FakeAdbServicesFactory("com.app") { it.addCommandOverride(Output("dumpsys backup users", "")) }
    val backupService = BackupServiceImpl(adbServicesFactory)

    val error = backupService.backup("serial", "com.app", CLOUD, backupFile, null) as Error

    val adbServices = adbServicesFactory.adbServices
    assertThat(error.errorCode).isEqualTo(BACKUP_MANAGER_IS_NOT_RUNNING)
    val backupException = error.throwable as BackupException
    assertThat(backupException.message).isEqualTo("Backup manager is not running for user 0")
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.app",
        "am get-current-user",
        "dumpsys backup users",
        "dumpsys backup users",
        "dumpsys backup users",
        "dumpsys backup users",
        "dumpsys backup users",
      )
  }

  @Test
  fun restore_backupManagerNotRunning(): Unit = runTest {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val adbServicesFactory = FakeAdbServicesFactory("com.app") { it.addCommandOverride(Output("dumpsys backup users", "")) }
    val backupService = BackupServiceImpl(adbServicesFactory)

    val error = backupService.restore("serial", backupFile, null) as Error

    val adbServices = adbServicesFactory.adbServices
    assertThat(error.errorCode).isEqualTo(BACKUP_MANAGER_IS_NOT_RUNNING)
    val backupException = error.throwable as BackupException
    assertThat(backupException.message).isEqualTo("Backup manager is not running for user 0")
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.app",
        "am get-current-user",
        "dumpsys backup users",
        "dumpsys backup users",
        "dumpsys backup users",
        "dumpsys backup users",
        "dumpsys backup users",
      )
  }
}

private fun Path.unzip(): Map<String, String> {
  ZipFile(pathString).use { zip ->
    return zip.entries().asSequence().associate { it.name to zip.getInputStream(it).reader().readText() }
  }
}
