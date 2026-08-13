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
package com.android.repository.impl.manager

import com.android.repository.api.ConsoleProgressIndicator
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for [LocalRepoFileWatcher]. */
class LocalRepoFileWatcherTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun testDetectPackageXmlCreation() {
    val repoRoot = temporaryFolder.newFolder("repo").toPath()
    val pkgDir = Files.createDirectories(repoRoot.resolve("platforms").resolve("android-34"))
    val watcher = createLocalRepoFileWatcher(repoRoot)
    drainEvents(watcher)

    Files.createFile(pkgDir.resolve(LocalRepoLoaderImpl.PACKAGE_XML_FN))
    assertTrue(waitForWatchEvents(watcher))
  }

  @Test
  fun testNoEventsWhenNothingChanged() {
    val repoRoot = temporaryFolder.newFolder().toPath()
    val watcher = createLocalRepoFileWatcher(repoRoot)
    drainEvents(watcher)

    assertFalse(watcher.consumeWatchEvents())
  }

  @Test
  fun testExcludedDirectoriesNotWatched() {
    val repoRoot = temporaryFolder.newFolder().toPath()
    val fontsDir = Files.createDirectories(repoRoot.resolve("fonts"))
    val skinsDir = Files.createDirectories(repoRoot.resolve("skins"))
    val watcher = createLocalRepoFileWatcher(repoRoot)
    drainEvents(watcher)

    Files.createFile(fontsDir.resolve("Roboto.ttf"))
    Files.createFile(skinsDir.resolve("layout.xml"))

    assertFalse(watcher.consumeWatchEvents())
  }

  @Test
  fun testHiddenFilesAndKnownPackagesIgnored() {
    val repoRoot = temporaryFolder.newFolder().toPath()
    val pkgDir = Files.createDirectories(repoRoot.resolve("platforms").resolve("android-34"))
    val watcher = createLocalRepoFileWatcher(repoRoot)
    drainEvents(watcher)

    // Writing .knownPackages or other hidden files should not trigger watch events
    Files.write(repoRoot.resolve(LocalRepoLoaderImpl.KNOWN_PACKAGES_HASH_FN), byteArrayOf(1, 2, 3))
    Files.write(pkgDir.resolve(".DS_Store"), byteArrayOf(4, 5, 6))

    assertFalse(watcher.consumeWatchEvents())
  }

  @Test
  fun testEmptyDirectoryCreationDoesNotTriggerUntilPackageXmlAdded() {
    val repoRoot = temporaryFolder.newFolder().toPath()
    val watcher = createLocalRepoFileWatcher(repoRoot)
    drainEvents(watcher)

    // Creating an empty directory without package.xml should not trigger a package reload
    val newPkgDir = Files.createDirectories(repoRoot.resolve("platforms").resolve("android-35"))
    assertFalse(watcher.consumeWatchEvents())

    // Once package.xml is written into the newly created directory, it should be detected
    Files.createFile(newPkgDir.resolve(LocalRepoLoaderImpl.PACKAGE_XML_FN))
    assertTrue(waitForWatchEvents(watcher))
  }

  @Test
  fun testArbitraryNonPackageFileChangesIgnored() {
    val repoRoot = temporaryFolder.newFolder().toPath()
    val platformsDir = Files.createDirectories(repoRoot.resolve("platforms"))
    val watcher = createLocalRepoFileWatcher(repoRoot)
    drainEvents(watcher)

    // Modifying/creating arbitrary non-package files in containers should not trigger watch events
    Files.createFile(platformsDir.resolve("notes.txt"))
    Files.write(repoRoot.resolve("temp.log"), byteArrayOf(1, 2, 3))

    assertFalse(watcher.consumeWatchEvents())
  }

  @Test
  fun testPackageDirectoryDeletionDetected() {
    val repoRoot = temporaryFolder.newFolder().toPath()
    val pkgDir = Files.createDirectories(repoRoot.resolve("platforms").resolve("android-34"))
    Files.createFile(pkgDir.resolve(LocalRepoLoaderImpl.PACKAGE_XML_FN))

    val watcher = createLocalRepoFileWatcher(repoRoot)
    drainEvents(watcher)

    Files.delete(pkgDir.resolve(LocalRepoLoaderImpl.PACKAGE_XML_FN))
    assertTrue(waitForWatchEvents(watcher))
  }

  @Test
  fun testPackageSubdirectoriesNotWatched() {
    val repoRoot = temporaryFolder.newFolder().toPath()
    val pkgDir = Files.createDirectories(repoRoot.resolve("system-images").resolve("android-34").resolve("google_apis").resolve("x86_64"))
    Files.createFile(pkgDir.resolve(LocalRepoLoaderImpl.PACKAGE_XML_FN))
    val internalSubdir = Files.createDirectories(pkgDir.resolve("data").resolve("res").resolve("values"))

    val watcher = createLocalRepoFileWatcher(repoRoot)
    drainEvents(watcher)

    // Modifying internal subdirectories inside a package should not trigger watch events
    Files.createFile(internalSubdir.resolve("strings.xml"))
    assertFalse(watcher.consumeWatchEvents())

    // Modifying package.xml at the package root should be detected
    Files.write(pkgDir.resolve(LocalRepoLoaderImpl.PACKAGE_XML_FN), byteArrayOf(1, 2, 3))
    assertTrue(waitForWatchEvents(watcher))
  }

  @Test
  fun testBlockingConsumeWatchEvents() {
    val repoRoot = temporaryFolder.newFolder().toPath()
    val pkgDir = Files.createDirectories(repoRoot.resolve("build-tools").resolve("34.0.0"))
    val watcher = createLocalRepoFileWatcher(repoRoot)
    drainEvents(watcher)

    val executor = Executors.newSingleThreadScheduledExecutor()
    try {
      executor.schedule({ Files.createFile(pkgDir.resolve(LocalRepoLoaderImpl.PACKAGE_XML_FN)) }, 50, TimeUnit.MILLISECONDS)

      assertTrue(watcher.consumeWatchEvents(blocking = true))
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun testCloseWatcher() {
    val repoRoot = temporaryFolder.newFolder().toPath()
    val watcher = createLocalRepoFileWatcher(repoRoot)
    watcher.close()

    assertThrows(ClosedWatchServiceException::class.java) { watcher.consumeWatchEvents() }
  }

  private fun drainEvents(watcher: LocalRepoFileWatcher, quietPeriodMs: Long = 50) {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < quietPeriodMs) {
      watcher.consumeWatchEvents()
      Thread.sleep(10)
    }
  }

  private fun waitForWatchEvents(watcher: LocalRepoFileWatcher, timeoutMs: Long = 5000): Boolean {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
      if (watcher.consumeWatchEvents()) {
        return true
      }
      Thread.sleep(10)
    }
    return false
  }

  private fun createLocalRepoFileWatcher(repoRoot: Path): LocalRepoFileWatcher =
    LocalRepoFileWatcher.create(repoRoot, ConsoleProgressIndicator())

  private fun LocalRepoFileWatcher.consumeWatchEvents(blocking: Boolean = false): Boolean =
    consumeWatchEvents(ConsoleProgressIndicator(), blocking)
}
