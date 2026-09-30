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

import com.android.repository.api.RepoManager
import com.android.repository.testframework.FakeProgressIndicator
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** Tests for the package directory scan in [LocalRepoLoaderImpl]. */
class LocalRepoLoaderImplScanTest {
  private val root: Path = createInMemoryFileSystemAndFolder("sdk")
  private val progress = FakeProgressIndicator()

  private fun loader(knownTopLevelDirs: Set<String> = emptySet()) =
    LocalRepoLoaderImpl(root, setOf(RepoManager.commonModule, RepoManager.genericModule), null, knownTopLevelDirs)

  /** Creates a package at [relativePath] under [root], with a package path matching its location. */
  private fun createPackage(relativePath: String) {
    writePackageXml(root.resolve(relativePath), relativePath.replace('/', ';'))
  }

  /** Writes a minimal generic package.xml into [dir]. */
  private fun writePackageXml(dir: Path, packagePath: String) {
    Files.createDirectories(dir)
    Files.writeString(
      dir.resolve(LocalRepoLoaderImpl.PACKAGE_XML_FN),
      """
      <repo:repository
          xmlns:repo="http://schemas.android.com/repository/android/generic/01"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
        <localPackage path="$packagePath">
          <type-details xsi:type="repo:genericDetailsType"/>
          <revision><major>1</major></revision>
          <display-name>$packagePath</display-name>
        </localPackage>
      </repo:repository>
      """
        .trimIndent(),
    )
  }

  @Test
  fun findsPackagesWithoutKnownTopLevelDirs() {
    createPackage("platforms/android-35")
    createPackage("random/package")

    val packages = loader().getPackages(progress)

    assertThat(packages.keys).containsExactly("platforms;android-35", "random;package")
    progress.assertNoErrorsOrWarnings()
  }

  @Test
  fun skipsOtherTopLevelDirsWhenNoKnownDirHasPackage() {
    // Looks like a home directory: a known directory name exists, but contains no packages.
    Files.createDirectories(root.resolve("platforms/not-a-package"))
    createPackage("Documents/some/package")

    val packages = loader(setOf("platforms", "build-tools")).getPackages(progress)

    assertThat(packages).isEmpty()
    assertThat(progress.warnings.filter { it.contains("Is the SDK location correct?") }).hasSize(1)
  }

  @Test
  fun rootPackageIsFound() {
    writePackageXml(root, "root")
    createPackage("platforms/android-35")

    val packages = loader(setOf("platforms")).getPackages(progress)

    assertThat(packages.values.map { it.location }).containsExactly(root)
  }

  @Test
  fun doesNotScanInsidePackages() {
    createPackage("extras/outer")
    createPackage("extras/outer/inner")

    val packages = loader().getPackages(progress)

    assertThat(packages.keys).containsExactly("extras;outer")
  }

  @Test
  fun skipsMetadataAndResourceCacheDirs() {
    createPackage("platforms/android-35")
    createPackage(".temp/package")
    createPackage("platforms/.downloadIntermediates/package")
    createPackage("skins/package")

    val packages = loader().getPackages(progress)

    assertThat(packages.keys).containsExactly("platforms;android-35")
  }

  @Test
  fun respectsMaxScanDepth() {
    // Packages may be at most 10 levels below the root.
    createPackage("extras/a/b/c/d/e/f/g/h/ten")
    createPackage("extras/z/b/c/d/e/f/g/h/i/eleven")

    val packages = loader().getPackages(progress)

    assertThat(packages.keys).containsExactly("extras;a;b;c;d;e;f;g;h;ten")
  }

  @Test
  fun followsSymlinksAndToleratesLoops() {
    createPackage("platforms/android-34")
    val elsewhere = root.parent.resolve("elsewhere/android-35")
    writePackageXml(elsewhere, "platforms;android-35")
    Files.createSymbolicLink(root.resolve("platforms/android-35"), elsewhere)
    // A symlink loop.
    Files.createDirectories(root.resolve("extras/dir"))
    Files.createSymbolicLink(root.resolve("extras/dir/loop"), root.resolve("extras"))

    val packages = loader().getPackages(progress)

    assertThat(packages.keys).containsExactly("platforms;android-34", "platforms;android-35")
    progress.assertNoErrorsOrWarnings()
  }
}
