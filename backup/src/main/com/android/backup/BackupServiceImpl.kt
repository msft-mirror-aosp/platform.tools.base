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

import com.android.backup.BackupResult.Success
import com.android.backup.BackupResult.WithoutAppData
import com.android.backup.BackupService.Companion.APP_DATA_FILE
import com.android.backup.BackupService.Companion.AUTH_DATA_FILE
import com.android.backup.BackupService.Companion.METADATA_FILE
import com.android.backup.BackupService.Companion.PERMISSIONS_FILE
import com.android.backup.BackupService.Companion.PM_DATA_FILE
import com.android.backup.BackupService.Companion.PROPERTY_APPLICATION_ID
import com.android.backup.BackupService.Companion.PROPERTY_BACKUP_TYPE
import com.android.backup.BackupService.Companion.TOKEN_FILE
import com.android.backup.BackupService.Companion.getMetaData
import com.android.backup.BackupService.Companion.getRestoreToken
import com.android.backup.ErrorCode.APP_NOT_INSTALLED
import com.android.backup.ErrorCode.BACKUP_NOT_ENABLED
import com.android.backup.ErrorCode.INVALID_BACKUP_FILE
import com.android.backup.ErrorCode.READ_CONTENT_FAILED
import com.android.backup.ErrorCode.WRITE_CONTENT_FAILED
import com.intellij.util.io.delete
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlinx.coroutines.withContext

private const val TRANSPORT_DTD = "com.google.android.gms/.backup.migrate.service.D2dTransport"
private const val TRANSPORT_CLOUD = "com.google.android.gms/.backup.BackupTransportService"
private const val CONTENT_URI =
  "content://com.google.android.gms.fileprovider/backup_testing_flows/"

internal class BackupServiceImpl(private val factory: AdbServicesFactory) : BackupService {

  override suspend fun backup(
    serialNumber: String,
    applicationId: String,
    type: BackupType,
    backupFile: Path,
    listener: BackupProgressListener?,
  ): BackupResult {
    val adbServices = factory.createAdbServices(serialNumber, listener, BACKUP_STEPS)
    return try {
      with(adbServices) {
        val tempFile = Files.createTempFile("", ".backup")
        if (!isInstalled(applicationId)) {
          throw BackupException(APP_NOT_INSTALLED, "Application '$applicationId' is not installed")
        }
        // Backup is always handled by the D2D transport
        withSetup(TRANSPORT_DTD) {
          reportProgress("Initializing backup transport")
          initializeTransport(TRANSPORT_DTD)
          try {
            reportProgress("Running backup")
            backupNow(applicationId, type)
            reportProgress("Fetching backup")
            pullBackup(adbServices, BackupMetadata(applicationId, type), tempFile)
          } finally {
            reportProgress("Cleaning up")
          }
        }
        reportProgress("Done")
        val result =
          when {
            isBackupEnabled(applicationId) -> Success
            tempFile.hasAuthData() -> WithoutAppData
            else ->
              BackupException(
                  BACKUP_NOT_ENABLED,
                  "No data was generated in backup since allowBackup property is false",
                )
                .toBackupResult()
          }
        if (result is BackupResult.Error) {
          tempFile.delete()
        } else {
          backupFile.parent.createDirectories()
          Files.move(tempFile, backupFile, REPLACE_EXISTING)
        }
        result
      }
    } catch (e: Throwable) {
      e.toBackupResult()
    }
  }

  override suspend fun restore(
    serialNumber: String,
    backupFile: Path,
    listener: BackupProgressListener?,
  ): BackupResult {
    return try {
      val adbServices = factory.createAdbServices(serialNumber, listener, RESTORE_STEPS)

      with(adbServices) {
        try {
          ZipFile(backupFile.pathString).use { zip ->
            val metadata = zip.getMetaData()
            val applicationId = metadata.applicationId
            if (!isInstalled(applicationId)) {
              throw BackupException(
                APP_NOT_INSTALLED,
                "Application '$applicationId' is not installed on the device",
              )
            }

            // Restore is always handled by the Cloud transport
            withSetup(TRANSPORT_DTD) {
              reportProgress("Initializing backup transport")
              initializeTransport(TRANSPORT_DTD)
              setTransport(TRANSPORT_CLOUD, true)
              val token = zip.getRestoreToken()
              reportProgress("Pushing backup file")
              pushBackup(zip)
              reportProgress("Clearing app data")
              clearAppData(applicationId)
              reportProgress("Restoring $applicationId")
              restore(token, applicationId, metadata.backupType)
              reportProgress("Restoring $applicationId permissions")
              zip.getPermissions().forEach { permission ->
                grantPermission(applicationId, permission)
              }
            }
          }
        } catch (e: IOException) {
          throw BackupException(
            INVALID_BACKUP_FILE,
            "File ${backupFile.pathString} is not a valid backup file",
            e,
          )
        }
        reportProgress("Done")
        Success
      }
    } catch (e: Throwable) {
      e.toBackupResult()
    }
  }

  override suspend fun sendUpdateGmsIntent(serialNumber: String): BackupResult {
    return try {
      factory.createAdbServices(serialNumber, null, 1).sendUpdateGmsIntent()
      Success
    } catch (e: Throwable) {
      e.toBackupResult()
    }
  }

  override suspend fun getForegroundApplicationId(serialNumber: String): String {
    return factory.createAdbServices(serialNumber, null, 1).getForegroundApplicationId()
  }

  override suspend fun isInstalled(serialNumber: String, applicationId: String): Boolean {
    return factory.createAdbServices(serialNumber, null, 1).isInstalled(applicationId)
  }

  override suspend fun isBackupEnabled(serialNumber: String, applicationId: String): Boolean {
    return factory.createAdbServices(serialNumber, null, 1).isBackupEnabled(applicationId)
  }

  override suspend fun isPlayStoreInstalled(serialNumber: String): Boolean {
    return factory.createAdbServices(serialNumber, null, 1).isPlayStoreInstalled()
  }

  private suspend fun pullBackup(
    adbServices: AdbServices,
    metadata: BackupMetadata,
    backupFile: Path,
  ) {
    ZipOutputStream(backupFile.outputStream()).use { zip ->
      adbServices.pullFileIntoZip(zip, TOKEN_FILE)
      adbServices.pullFileIntoZip(zip, PM_DATA_FILE)
      adbServices.pullFileIntoZip(zip, APP_DATA_FILE)
      try {
        adbServices.pullFileIntoZip(zip, AUTH_DATA_FILE)
        adbServices.pullFileIntoZip(zip, PERMISSIONS_FILE)
      } catch (e: BackupException) {
        // older versions of GmsCore may not have AUTH backup support
        if (e.errorCode != READ_CONTENT_FAILED) {
          throw e
        }
      }
      zip.putMetadata(adbServices, metadata)
    }
  }

  private suspend fun AdbServices.pushBackup(zip: ZipFile) {
    pushFileFromZip(zip, TOKEN_FILE)
    pushFileFromZip(zip, PM_DATA_FILE)
    pushFileFromZip(zip, APP_DATA_FILE)
    val authEntry: ZipEntry? = zip.getEntry(AUTH_DATA_FILE)
    if (authEntry != null) {
      // Backup files from older version will not have the auth file and backups
      try {
        pushFileFromZip(zip, AUTH_DATA_FILE)
      } catch (e: BackupException) {
        // older versions of GmsCore may not have AUTH backup support
        if (e.errorCode != WRITE_CONTENT_FAILED) {
          throw e
        }
      }
    }
  }

  private suspend fun AdbServices.pullFileIntoZip(zip: ZipOutputStream, name: String) {
    withContext(ioContext) { zip.putNextEntry(ZipEntry(name)) }
    readContent(zip, CONTENT_URI + name)
  }

  private suspend fun AdbServices.pushFileFromZip(zip: ZipFile, name: String) {
    withContext(ioContext) {
      writeContent(zip.getInputStream(zip.getEntry(name)), "$CONTENT_URI$name")
    }
  }

  private suspend fun ZipOutputStream.putMetadata(
    adbServices: AdbServices,
    metadata: BackupMetadata,
  ) {
    withContext(adbServices.ioContext) {
      putNextEntry(ZipEntry(METADATA_FILE))
      val properties = Properties()
      properties[PROPERTY_APPLICATION_ID] = metadata.applicationId
      properties[PROPERTY_BACKUP_TYPE] = metadata.backupType.name
      properties.store(this@putMetadata, null)
    }
  }

  companion object {

    const val BACKUP_STEPS = 10
    const val RESTORE_STEPS = 11
  }
}

private fun Path.hasAuthData(): Boolean {
  return ZipFile(this.pathString).use { it.getEntry(AUTH_DATA_FILE).size > 0 }
}

internal fun ZipFile.getPermissions(): List<String> {
  return try {
    getInputStream(getEntry(PERMISSIONS_FILE)).reader().readLines()
  } catch (_: Exception) {
    emptyList()
  }
}
