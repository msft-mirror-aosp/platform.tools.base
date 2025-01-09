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
import com.android.backup.BackupType.CLOUD
import com.android.backup.BackupType.CLOUD_UNENCRYPTED
import com.android.backup.BackupType.DEVICE_TO_DEVICE
import com.android.backup.ErrorCode.APP_STOPPED
import com.android.backup.ErrorCode.BACKUP_FAILED
import com.android.backup.ErrorCode.BACKUP_NOT_ALLOWED
import com.android.backup.ErrorCode.CANNOT_ENABLE_BMGR
import com.android.backup.ErrorCode.GMSCORE_NOT_FOUND
import com.android.backup.ErrorCode.INVALID_BACKUP_FILE
import com.android.backup.ErrorCode.RESTORE_FAILED
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
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.pathString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val TRANSPORT_NOT_SET_MESSAGE =
  "Requested transport was not set: Selected transport com.google.android.gms/.backup.migrate.service.D2dTransport (formerly com.google.android.gms/.backup.BackupTransportService)"

class BackupServiceImplTest {

  @get:Rule val temporaryFolder = TemporaryFolder()
  private val backupFileHelper = BackupFileHelper(temporaryFolder)

  @Test
  fun backup_d2d(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory()
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr transport com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "settings put secure backup_testing_flows_type 0",
        "bmgr backupnow @pm@ com.app --non-incremental --monitor",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
    val files = backupFile.unzip()
    assertThat(files.keys)
      .containsExactly("pm_backup", "restore_token_file", "app_backup", "metadata.txt")
    assertThat(adbServices.testMode).isEqualTo(0)
    assertThat(backupFile.exists()).isTrue()
    assertThat(files["pm_backup"])
      .isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/pm_backup")
    assertThat(files["restore_token_file"])
      .isEqualTo(
        "content://com.google.android.gms.fileprovider/backup_testing_flows/restore_token_file"
      )
    assertThat(files["app_backup"])
      .isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/app_backup")
    val metadata = BackupService.getMetadata(backupFile)
    assertThat(metadata).isEqualTo(BackupMetadata("com.app", DEVICE_TO_DEVICE))
  }

  @Test
  fun backup_cloud(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory()
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.backup("serial", "com.app", CLOUD, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr transport com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "settings put secure backup_testing_flows_type 1",
        "bmgr backupnow @pm@ com.app --non-incremental --monitor",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
    assertThat(adbServices.testMode).isEqualTo(0)
    assertThat(backupFile.exists()).isTrue()
    val files = backupFile.unzip()
    assertThat(files.keys)
      .containsExactly("pm_backup", "restore_token_file", "app_backup", "metadata.txt")
    assertThat(files["pm_backup"])
      .isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/pm_backup")
    assertThat(files["restore_token_file"])
      .isEqualTo(
        "content://com.google.android.gms.fileprovider/backup_testing_flows/restore_token_file"
      )
    assertThat(files["app_backup"])
      .isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/app_backup")
    val metadata = BackupService.getMetadata(backupFile)
    assertThat(metadata).isEqualTo(BackupMetadata("com.app", CLOUD))
  }

  @Test
  fun backup_cloudUnencrypted(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory()
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.backup("serial", "com.app", CLOUD_UNENCRYPTED, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr transport com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "settings put secure backup_testing_flows_type 2",
        "bmgr backupnow @pm@ com.app --non-incremental --monitor",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
    assertThat(adbServices.testMode).isEqualTo(0)
    assertThat(backupFile.exists()).isTrue()
    val files = backupFile.unzip()
    assertThat(files.keys)
      .containsExactly("pm_backup", "restore_token_file", "app_backup", "metadata.txt")
    assertThat(files["pm_backup"])
      .isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/pm_backup")
    assertThat(files["restore_token_file"])
      .isEqualTo(
        "content://com.google.android.gms.fileprovider/backup_testing_flows/restore_token_file"
      )
    assertThat(files["app_backup"])
      .isEqualTo("content://com.google.android.gms.fileprovider/backup_testing_flows/app_backup")
    val metadata = BackupService.getMetadata(backupFile)
    assertThat(metadata).isEqualTo(BackupMetadata("com.app", CLOUD_UNENCRYPTED))
  }

  @Test
  fun backup_bmgrAlreadyEnabled(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory { it.bmgrEnabled = true }
    val backupServices = BackupServiceImpl(adbServicesFactory)

    val result = backupServices.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr transport com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "settings put secure backup_testing_flows_type 0",
        "bmgr backupnow @pm@ com.app --non-incremental --monitor",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
      )
      .inOrder()
  }

  @Test
  fun backup_transportAlreadySelected(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory {
      it.activeTransport = "com.google.android.gms/.backup.migrate.service.D2dTransport"
    }
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr transport com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "settings put secure backup_testing_flows_type 0",
        "bmgr backupnow @pm@ com.app --non-incremental --monitor",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
  }

  @Test
  fun backup_assertProgress(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val adbServicesFactory = FakeAdbServicesFactory()
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
  fun backup_deletesExistingFileBeforeRunning(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    backupFile.createFile()
    val backupService = BackupServiceImpl(FakeAdbServicesFactory { it.transports = emptyList() })

    backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(backupFile.notExists()).isTrue()
  }

  @Test
  fun backup_pullFails_deletesFile(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService = BackupServiceImpl(FakeAdbServicesFactory { it.failReadWriteContent = true })

    backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(backupFile.notExists()).isTrue()
  }

  @Test
  fun backup_initTransportFails(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory {
          it.addCommandOverride(
            Throw("bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport")
          )
        }
      )

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(result).isEqualTo(TRANSPORT_INIT_FAILED.asBackupResult())
  }

  @Test
  fun backup_gmsCoreNotFound(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory {
          it.addCommandOverride(
            Output(
              "dumpsys package com.google.android.gms",
              "dumpsys package com.google.android.gms",
            )
          )
        }
      )

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(result)
      .isEqualTo(GMSCORE_NOT_FOUND.asBackupResult("Google Services not found on device"))
  }

  @Test
  fun backup_backupFailed(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory {
          it.addCommandOverride(
            Output("bmgr backupnow @pm@ com.app --non-incremental --monitor", "Error")
          )
        }
      )

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(result).isEqualTo(BACKUP_FAILED.asBackupResult("Failed to backup 'com.app`: Error"))
  }

  @Test
  fun backup_backupNotAllowed(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory {
          it.addCommandOverride(
            Output(
              "bmgr backupnow @pm@ com.app --non-incremental --monitor",
              "Package com.app with result: Backup is not allowed",
            )
          )
        }
      )

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(result)
      .isEqualTo(
        BACKUP_NOT_ALLOWED.asBackupResult(
          "Backup not allowed. Please ensure manifest value 'android:allowBackup' is set to true."
        )
      )
  }

  @Test
  fun backup_appStopped(): Unit = runBlocking {
    val backupFile = Path.of(temporaryFolder.root.path, "file.backup")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory {
          it.addCommandOverride(
            Output(
              "bmgr backupnow @pm@ com.app --non-incremental --monitor",
              "=> Event{BACKUP_MANAGER_POLICY / PACKAGE_STOPPED : package = com.app(v1)}",
            )
          )
        }
      )

    val result = backupService.backup("serial", "com.app", DEVICE_TO_DEVICE, backupFile, null)

    assertThat(result)
      .isEqualTo(
        APP_STOPPED.asBackupResult(
          "Application 'com.app' is in a stopped state. Please launch the app and try again."
        )
      )
  }

  @Test
  fun restore_cloud(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900", CLOUD)
    val adbServicesFactory = FakeAdbServicesFactory()
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.restore("serial", backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr transport com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "bmgr list transports",
        "settings put secure backup_testing_flows_type 1",
        "bmgr restore 9bc1546914997f6c com.app",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
    assertThat(adbServices.testMode).isEqualTo(0)
  }

  @Test
  fun restore_d2d(): Unit = runBlocking {
    val backupFile =
      backupFileHelper.createBackupFile("com.app", "11223344556677889900", DEVICE_TO_DEVICE)
    val adbServicesFactory = FakeAdbServicesFactory()
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.restore("serial", backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr transport com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "bmgr list transports",
        "settings put secure backup_testing_flows_type 0",
        "bmgr restore 9bc1546914997f6c com.app",
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
    val adbServicesFactory = FakeAdbServicesFactory { it.bmgrEnabled = true }
    val backupService = BackupServiceImpl(adbServicesFactory)

    val result = backupService.restore("serial", backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    (result as? Error)?.throwable?.printStackTrace()
    assertThat(result).isEqualTo(Success)
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr transport com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "bmgr list transports",
        "settings put secure backup_testing_flows_type 1",
        "bmgr restore 9bc1546914997f6c com.app",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "settings put secure backup_enable_testing_flows 0",
      )
      .inOrder()
  }

  @Test
  fun restore_transportNotSet(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val adbServicesFactory = FakeAdbServicesFactory {
      it.activeTransport = "com.android.localtransport/.LocalTransport"
    }
    val backupService = BackupServiceImpl(adbServicesFactory)

    backupService.restore("serial", backupFile, null)

    val adbServices = adbServicesFactory.adbServices
    assertThat(adbServices.getCommands())
      .containsExactly(
        "dumpsys package com.google.android.gms",
        "bmgr enabled",
        "bmgr enable true",
        "settings put secure backup_enable_testing_flows 1",
        "bmgr transport com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr list transports",
        "bmgr init com.google.android.gms/.backup.migrate.service.D2dTransport",
        "bmgr transport com.google.android.gms/.backup.BackupTransportService",
        "bmgr list transports",
        "settings put secure backup_testing_flows_type 1",
        "bmgr restore 9bc1546914997f6c com.app",
        "bmgr transport com.android.localtransport/.LocalTransport",
        "settings put secure backup_enable_testing_flows 0",
        "bmgr enable false",
      )
      .inOrder()
  }

  @Test
  fun restore_assertProgress(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val adbServicesFactory = FakeAdbServicesFactory()
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
        "8/14: Restoring com.app",
        "9/14: Restoring backup transport",
        "10/14: Disabling test mode",
        "11/14: Disabling BMGR",
        "12/14: Done",
      )
      .inOrder()
  }

  @Test
  fun validateBackupFile() {
    BackupService.validateBackupFile(
      backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    )
  }

  @Test
  fun validateBackupFile_noApplicationId() {
    assertThrows(BackupException::class.java) {
      BackupService.validateBackupFile(
        backupFileHelper.createZipFile(
          FileInfo("@pm@", ""),
          FileInfo("restore_token_file", "11223344556677889900"),
        )
      )
    }
  }

  @Test
  fun validateBackupFile_invalidToken() {
    assertThrows(BackupException::class.java) {
      BackupService.validateBackupFile(backupFileHelper.createBackupFile("com.app", "foobar"))
    }
  }

  @Test
  fun validateBackupFile_notZipFile() {
    assertThrows(ZipException::class.java) {
      val path = Path.of(temporaryFolder.root.path, "file.backup").createFile()
      BackupService.validateBackupFile(path)
    }
  }

  @Test
  fun validateBackupFile_unexpectedFile() {
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
  }

  @Test
  fun validateBackupFile_missingPmFile() {
    assertThrows(BackupException::class.java) {
      BackupService.validateBackupFile(
        backupFileHelper.createZipFile(
          FileInfo("restore_token_file", "11223344556677889900"),
          FileInfo("com.app", ""),
        )
      )
    }
  }

  @Test
  fun restore_enableBmgrFails(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val backupService =
      BackupServiceImpl(FakeAdbServicesFactory { it.addCommandOverride(Throw("bmgr enabled")) })

    val result = backupService.restore("serial", backupFile, null)

    assertThat(result).isEqualTo(CANNOT_ENABLE_BMGR.asBackupResult())
  }

  @Test
  fun restore_setTransportFails(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory { it.addCommandOverride(Output("bmgr list transports", "")) }
      )

    val result = backupService.restore("serial", backupFile, null)

    assertThat(result).isEqualTo(TRANSPORT_NOT_SELECTED.asBackupResult(TRANSPORT_NOT_SET_MESSAGE))
  }

  @Test
  fun restore_invalidBackupFile(): Unit = runBlocking {
    val backupFile = backupFileHelper.createZipFile(FileInfo("some-file", ""))

    val backupService = BackupServiceImpl(FakeAdbServicesFactory())

    val error =
      backupService.restore("serial", backupFile, null) as? Error ?: fail("Expected an Error")

    assertThat(error.errorCode).isEqualTo(INVALID_BACKUP_FILE)
    assertThat(error.throwable.message)
      .isEqualTo("Backup file does not contain a valid token: ${backupFile.pathString}")
  }

  @Test
  fun restore_restoreFailed(): Unit = runBlocking {
    val backupFile = backupFileHelper.createBackupFile("com.app", "11223344556677889900")
    val backupService =
      BackupServiceImpl(
        FakeAdbServicesFactory {
          it.addCommandOverride(Output("bmgr restore 9bc1546914997f6c com.app", "Error"))
        }
      )

    val result = backupService.restore("serial", backupFile, null)

    assertThat(result).isEqualTo(RESTORE_FAILED.asBackupResult("Error restoring app: Error"))
  }
}

private fun Path.unzip(): Map<String, String> {
  ZipFile(pathString).use { zip ->
    return zip.entries().asSequence().associate {
      it.name to zip.getInputStream(it).reader().readText()
    }
  }
}
