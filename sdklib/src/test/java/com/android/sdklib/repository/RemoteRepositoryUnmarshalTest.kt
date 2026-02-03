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
package com.android.sdklib.repository

import com.android.repository.Revision
import com.android.repository.api.Repository
import com.android.repository.impl.meta.SchemaModuleUtil
import com.android.repository.testframework.FakeProgressIndicator
import com.android.sdklib.AndroidVersion
import com.android.sdklib.repository.meta.DetailsTypes
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class RemoteRepositoryUnmarshalTest {

  private val modules = AndroidSdkHandler.getAllModules()
  private val progress = FakeProgressIndicator()

  @Test
  fun testRemoteV1() {
    unmarshalAndVerify("remote/v1/repo.xml") { repo ->
      assertThat(repo.license).hasSize(2)
      assertThat(repo.license.map { it.id }).containsExactly("license1", "license2")
      assertThat(repo.license.find { it.id == "license1" }?.value?.trim()?.replace(Regex("\\s+"), " "))
        .isEqualTo("This is the license for this platform.")

      val packages = repo.remotePackage.associateBy { it.path }
      assertThat(packages).hasSize(3)

      // Platform
      val platform = packages["platforms;android-22"]!!
      assertThat(platform.displayName).isEqualTo("Lollipop MR1")
      assertThat(platform.version).isEqualTo(Revision(3))
      val platformDetails = platform.typeDetails as DetailsTypes.PlatformDetailsType
      assertThat(platformDetails.apiLevel).isEqualTo(1)
      assertThat(platformDetails.layoutlib.api).isEqualTo(5)

      // Docs
      val docs = packages["docs"]!!
      assertThat(docs.displayName).isEqualTo("Documentation")
      assertThat(docs.version).isEqualTo(Revision(43))

      // Sources
      val sources = packages["sources;android-1"]!!
      assertThat(sources.displayName).isEqualTo("Sources for android-1")
      assertThat(sources.version).isEqualTo(Revision(1))
      val sourcesDetails = sources.typeDetails as DetailsTypes.SourceDetailsType
      assertThat(sourcesDetails.apiLevel).isEqualTo(1)
    }
  }

  @Test
  fun testRemoteV2() {
    unmarshalAndVerify("remote/v2/repo.xml") { repo ->
      val packages = repo.remotePackage.associateBy { it.path }
      assertThat(packages).hasSize(3)

      val platform = packages["platforms;android-22"]!!
      val platformDetails = platform.typeDetails as DetailsTypes.PlatformDetailsType
      assertThat(platformDetails.apiLevel).isEqualTo(1)

      val sources = packages["sources;android-1"]!!
      val sourcesDetails = sources.typeDetails as DetailsTypes.SourceDetailsType
      assertThat(sourcesDetails.apiLevel).isEqualTo(1)
      assertThat(sources.archive!!.complete.typedChecksum.value)
        .isEqualTo("1234ae37115ebf13412bbef91339ee0d945412341339ee0d9454123494541234")
    }
  }

  @Test
  fun testRemoteV3() {
    unmarshalAndVerify("remote/v3/repo.xml") { repo ->
      val packages = repo.remotePackage.associateBy { it.path }
      assertThat(packages).hasSize(3)

      // Platform
      val platform = packages["platforms;android-22"]!!
      val platformDetails = platform.typeDetails as DetailsTypes.PlatformDetailsType
      assertThat(platformDetails.apiLevel).isEqualTo(22)
      assertThat(platformDetails.apiLevelString).isEqualTo("22x")
      assertThat(platformDetails.extensionLevel).isEqualTo(2)
      assertThat(platformDetails.isBaseExtension).isFalse()
      assertThat(platformDetails.androidVersion).isEqualTo(AndroidVersion(22, null, 2, false))

      // Sources
      val sources = packages["sources;android-1"]!!
      val sourcesDetails = sources.typeDetails as DetailsTypes.SourceDetailsType
      assertThat(sourcesDetails.apiLevel).isEqualTo(1)
      assertThat(sourcesDetails.isBaseExtension).isTrue()
      assertThat(sourcesDetails.androidVersion).isEqualTo(AndroidVersion(1))
    }
  }

  @Test
  fun testRemoteV4() {
    unmarshalAndVerify("remote/v4/repo.xml") { repo ->
      val packages = repo.remotePackage.associateBy { it.path }
      assertThat(packages).hasSize(3)

      // Platform
      val platform = packages["platforms;android-canary-20260101"]!!
      val platformDetails = platform.typeDetails as DetailsTypes.PlatformDetailsType
      assertThat(platformDetails.apiLevel).isEqualTo(36)
      assertThat(platformDetails.apiLevelString).isEqualTo("36.1")
      assertThat(platformDetails.isBaseExtension).isTrue()
      assertThat(platformDetails.androidVersion).isEqualTo(AndroidVersion(36, 1).withCanaryNumber(20260101))

      // Sources
      val sources = packages["sources;android-37.0-beta2"]!!
      val sourcesDetails = sources.typeDetails as DetailsTypes.SourceDetailsType
      assertThat(sourcesDetails.apiLevel).isEqualTo(36)
      assertThat(sourcesDetails.betaApiLevel).isEqualTo("37.0")
      assertThat(sourcesDetails.betaNumber).isEqualTo(2)
      assertThat(sourcesDetails.androidVersion).isEqualTo(AndroidVersion(37, 0).withBetaNumber(2))
      assertThat(sourcesDetails.isBaseExtension).isTrue()
    }
  }

  private fun unmarshalAndVerify(relativePath: String, verification: (Repository) -> Unit) {
    val path = "/com/android/sdklib/repository/testdata/$relativePath"
    val inputStream = this::class.java.getResourceAsStream(path)
    assertWithMessage("Resource at $path").that(inputStream).isNotNull()

    val repo = SchemaModuleUtil.unmarshal(inputStream, modules, true, progress, relativePath) as Repository
    progress.assertNoErrorsOrWarnings()
    verification(repo)
  }
}
