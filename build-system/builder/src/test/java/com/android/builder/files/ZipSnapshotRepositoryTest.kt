/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.builder.files

import com.android.zipflinger.ZipMap
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.test.assertFailsWith
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for [ZipSnapshotRepository]. */
class ZipSnapshotRepositoryTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var cacheDir: File
  private lateinit var otherDir: File
  private lateinit var repository: ZipSnapshotRepository

  @Before
  fun before() {
    cacheDir = temporaryFolder.newFolder("repository_temp")
    otherDir = temporaryFolder.newFolder("other")
    repository = ZipSnapshotRepository(cacheDir)
  }

  @Test
  fun addZipSnapshot() {
    val file = createZipA()

    repository.takeSnapshotOfZip(file)
    val snapshot = repository.getLastSnapshotOfZip(file)

    assertThat(snapshot).isNotNull()
    assertThat(snapshot?.dump()).isEqualTo(file.dump())
  }

  @Test
  fun addMultipleZipSnapshot() {
    val file1 = createZipA()
    val file2 = createZipB()

    repository.takeSnapshotOfZip(file1)
    repository.takeSnapshotOfZip(file2)

    val snapshot1 = repository.getLastSnapshotOfZip(file1)
    val snapshot2 = repository.getLastSnapshotOfZip(file2)

    assertThat(snapshot1).isNotNull()
    assertThat(snapshot2).isNotNull()
    assertThat(snapshot1).isNotEqualTo(snapshot2)
  }

  @Test
  fun getZipSnapshotOfNotSeenFile() {
    val file = createZipA()
    val snapshot = repository.getLastSnapshotOfZip(file)
    assertThat(snapshot).isNull()
  }

  @Test
  fun updatingZipSnapshot() {
    val file1 = createZipA()
    repository.takeSnapshotOfZip(file1)
    val snapshot1 = repository.getLastSnapshotOfZip(file1)

    val file2 = createZipB()
    repository.takeSnapshotOfZip(file2)
    val snapshot2 = repository.getLastSnapshotOfZip(file2)

    assertThat(snapshot1).isNotEqualTo(snapshot2)
    assertThat(snapshot1?.entryList?.entries?.size).isEqualTo(ZIP_A_ENTRIES_COUNT)
    assertThat(snapshot2?.entryList?.entries?.size).isEqualTo(ZIP_B_ENTRIES_COUNT)
  }

  @Test
  fun removingZipSnapshot() {
    val file1 = createZipA()
    val file2 = createZipB()

    repository.takeSnapshotOfZip(file1)
    repository.takeSnapshotOfZip(file2)

    repository.removeSnapshotOfZip(file1)

    assertThat(repository.getLastSnapshotOfZip(file1)).isNull()
    assertThat(repository.getLastSnapshotOfZip(file2)).isNotNull()
  }

  @Test
  fun clearingZipRepository() {
    val file1 = createZipA()
    val file2 = createZipB()

    repository.takeSnapshotOfZip(file1)
    repository.takeSnapshotOfZip(file2)

    repository.clear()

    assertThat(repository.getLastSnapshotOfZip(file1)).isNull()
    assertThat(repository.getLastSnapshotOfZip(file2)).isNull()
  }

  @Test
  fun cannotRemoveWhatHasNotBeenAdded() {
    val exception = assertFailsWith<IllegalArgumentException> { repository.removeSnapshotOfZip(createZipA()) }
    assertThat(exception.message).startsWith("Trying to remove a snapshot that doesn't exist")
  }

  private fun createZipA(): File {
    return createZip(name = ZIP_A_NAME, fileCount = ZIP_A_ENTRIES_COUNT)
  }

  private fun createZipB(): File {
    return createZip(name = ZIP_B_NAME, fileCount = ZIP_B_ENTRIES_COUNT)
  }

  private fun createZip(name: String, fileCount: Int) =
    ZipTestTool.createZipFile(
      otherDir.absolutePath + "/" + name,
      *((1..fileCount).map { listOf("$it.txt", "Content for file $it") }).flatten().toTypedArray(),
    )

  private fun ZipSnapshot.dump(): String {
    return entryList.entries.toList().joinToString { (name, entry) -> "$name, ${entry.name}, ${entry.crc}, ${entry.size}" }
  }

  private fun File.dump(): String {
    return ZipMap.from(toPath()).entries.toList().joinToString { (name, entry) ->
      "$name, ${entry.name}, ${entry.crc}, ${entry.uncompressedSize}"
    }
  }

  companion object {

    const val ZIP_A_NAME = "a.zip"
    const val ZIP_A_ENTRIES_COUNT = 3
    const val ZIP_B_NAME = "b.zip"
    const val ZIP_B_ENTRIES_COUNT = 4
  }
}
