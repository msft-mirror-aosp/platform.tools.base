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

import com.android.adblib.AdbSession
import com.android.backup.ErrorCode.INVALID_BACKUP_FILE
import com.android.tools.environment.Logger
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.io.path.pathString

interface BackupService {

  suspend fun backup(
    serialNumber: String,
    applicationId: String,
    type: BackupType,
    backupFile: Path,
    listener: BackupProgressListener?,
  ): BackupResult

  suspend fun restore(
    serialNumber: String,
    backupFile: Path,
    listener: BackupProgressListener?,
  ): BackupResult

  suspend fun sendUpdateGmsIntent(serialNumber: String): BackupResult

  suspend fun getForegroundApplicationId(serialNumber: String): String

  suspend fun isInstalled(serialNumber: String, applicationId: String): Boolean

  suspend fun isBackupEnabled(serialNumber: String, applicationId: String): Boolean

  suspend fun isPlayStoreInstalled(serialNumber: String): Boolean

  companion object {

    const val TOKEN_FILE = "restore_token_file"
    const val PM_DATA_FILE = "pm_backup"
    const val APP_DATA_FILE = "app_backup"
    const val AUTH_DATA_FILE = "auth_backup"
    const val PERMISSIONS_FILE = "permissions"
    const val METADATA_FILE = "metadata.txt"
    const val PROPERTY_APPLICATION_ID = "application-id"
    const val PROPERTY_BACKUP_TYPE = "backup-type"

    fun getInstance(adbSession: AdbSession, logger: Logger, minGmsVersion: Int): BackupService =
      BackupServiceImpl(AdbServicesFactoryImpl(adbSession, logger, minGmsVersion))

    fun getInstance(adbServicesFactory: AdbServicesFactory): BackupService =
      BackupServiceImpl(adbServicesFactory)

    /**
     * Verifies a backup file is valid and returns the application id of the associated app
     *
     * @param backupFile The path of a backup file to validate
     * @return The backup metadata from the backup file
     * @throws Exception `backupFile` is not valid
     */
    fun validateBackupFile(backupFile: Path): BackupMetadata {
      try {
        ZipFile(backupFile.pathString).use { zip ->
          val metadata = zip.getMetaData()
          zip.getRestoreToken()
          val filenames = zip.entries().asSequence().mapTo(mutableSetOf()) { it.name }
          if (!filenames.containsAll(listOf(PM_DATA_FILE, TOKEN_FILE, APP_DATA_FILE))) {
            throw BackupException(
              INVALID_BACKUP_FILE,
              "File is not a valid backup file: ${backupFile.pathString} ($filenames)",
            )
          }
          return metadata
        }
      } catch (e: IOException) {
        throw BackupException(
          INVALID_BACKUP_FILE,
          "File is not a valid backup file: ${backupFile.pathString}",
          e,
        )
      }
    }

    internal fun ZipFile.getRestoreToken(): String {
      return try {
        BigInteger(getInputStream(getEntry(TOKEN_FILE)).reader().readText()).toString(16)
      } catch (e: Exception) {
        throw BackupException(
          INVALID_BACKUP_FILE,
          "Backup file does not contain a valid token: $name",
          e,
        )
      }
    }

    internal fun ZipFile.getMetaData(): BackupMetadata {
      return try {
        val properties = Properties()
        properties.load(getInputStream(getEntry(METADATA_FILE)))
        BackupMetadata(
          properties.getProperty(PROPERTY_APPLICATION_ID),
          BackupType.valueOf(properties.getProperty(PROPERTY_BACKUP_TYPE)),
        )
      } catch (e: Exception) {
        throw BackupException(
          INVALID_BACKUP_FILE,
          "Backup file does not contain metadata: $name",
          e,
        )
      }
    }

    fun getMetadata(backupFile: Path): BackupMetadata {
      val zipFile =
        try {
          ZipFile(backupFile.toFile())
        } catch (e: Exception) {
          throw BackupException(
            INVALID_BACKUP_FILE,
            "File is not a valid backup file: $backupFile",
            e,
          )
        }
      return zipFile.use { it.getMetaData() }
    }
  }
}
