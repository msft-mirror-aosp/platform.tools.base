/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.backup.testing

import com.android.backup.BackupType
import com.android.backup.BackupType.CLOUD
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import org.junit.rules.TemporaryFolder

class BackupFileHelper(private val temporaryFolder: TemporaryFolder) {
  class FileInfo(val name: String, val contents: String)

  fun createBackupFile(
    applicationId: String,
    token: String,
    backupType: BackupType = CLOUD,
    withAuth: Boolean = true,
    permissions: List<String> = emptyList(),
  ): Path {
    val files =
      buildList {
          add(FileInfo("pm_backup", ""))
          add(FileInfo("app_backup", ""))
          add(FileInfo("restore_token_file", token))
          add(
            FileInfo(
              "metadata.txt",
              """
                application-id=$applicationId
                backup-type=${backupType.name}
              """
                .trimIndent(),
            )
          )
          if (withAuth) {
            add(FileInfo("auth_backup", ""))
          }
          add(FileInfo("permissions", permissions.joinToString("\n") { it }))
        }
        .toTypedArray()
    return createZipFile(*files)
  }

  @Suppress("SameParameterValue")
  fun createZipFile(vararg files: FileInfo): Path {
    val path = Path.of(temporaryFolder.root.path, "file.backup")
    ZipOutputStream(path.outputStream()).use { zip ->
      files.forEach {
        zip.putNextEntry(ZipEntry(it.name))
        zip.write(it.contents.toByteArray())
      }
    }
    return path
  }
}
