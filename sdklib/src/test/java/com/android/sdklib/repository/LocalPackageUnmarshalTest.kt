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
import com.android.repository.api.LocalPackage
import com.android.repository.api.Repository
import com.android.repository.impl.meta.SchemaModuleUtil
import com.android.repository.testframework.FakeProgressIndicator
import com.android.sdklib.AndroidVersion
import com.android.sdklib.repository.meta.DetailsTypes
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class LocalPackageUnmarshalTest {

  private val modules = AndroidSdkHandler.getAllModules()
  private val progress = FakeProgressIndicator()

  @Test
  fun testPlatformV1() {
    unmarshalAndVerify("platform/v1/platform.xml") { pkg ->
      assertThat(pkg.path).isEqualTo("platforms;android-22")
      assertThat(pkg.version).isEqualTo(Revision(1, 2, 3))
      assertThat(pkg.displayName).isEqualTo("Sample platform v1")
      assertThat(pkg.obsolete()).isTrue()
      assertThat(pkg.license!!.value.trim()).isEqualTo("Sample license")
      assertThat(pkg.allDependencies).hasSize(1)
      val dep = pkg.allDependencies.first()
      assertThat(dep.path).isEqualTo("tools")
      assertThat(dep.minRevision!!.toRevision()).isEqualTo(Revision(24))

      val details = pkg.typeDetails as DetailsTypes.PlatformDetailsType
      assertThat(details.apiLevel).isEqualTo(22)
      assertThat(details.layoutlib.api).isEqualTo(5)
      assertThat(details.androidVersion).isEqualTo(AndroidVersion(22))
      assertThat(details.abis).containsExactly("armeabi")
    }
  }

  @Test
  fun testSourceV1() {
    unmarshalAndVerify("source/v1/source.xml") { pkg ->
      assertThat(pkg.path).isEqualTo("sources;android-22")
      assertThat(pkg.version).isEqualTo(Revision(10, 11))
      assertThat(pkg.displayName).isEqualTo("Sample source v1")
      assertThat(pkg.obsolete()).isFalse()

      val details = pkg.typeDetails as DetailsTypes.SourceDetailsType
      assertThat(details.apiLevel).isEqualTo(22)
      // getAbis() throws for SourceDetailsType
    }
  }

  @Test
  fun testPlatformV2() {
    unmarshalAndVerify("platform/v2/platform.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.PlatformDetailsType
      assertThat(details.apiLevel).isEqualTo(23)
      assertThat(details.layoutlib.api).isEqualTo(6)
      assertThat(details.abis).containsExactly("armeabi")
    }
  }

  @Test
  fun testSourceV2() {
    unmarshalAndVerify("source/v2/source.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.SourceDetailsType
      assertThat(details.apiLevel).isEqualTo(23)
    }
  }

  @Test
  fun testPlatformV3() {
    unmarshalAndVerify("platform/v3/platform.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.PlatformDetailsType
      assertThat(details.apiLevel).isEqualTo(24)
      assertThat(details.extensionLevel).isEqualTo(3)
      assertThat(details.isBaseExtension).isFalse()
      assertThat(details.layoutlib.api).isEqualTo(7)
      assertThat(details.androidVersion).isEqualTo(AndroidVersion(24, null, 3, false))
      assertThat(details.abis).containsExactly("armeabi")
    }
  }

  @Test
  fun testSourceV3() {
    unmarshalAndVerify("source/v3/source.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.SourceDetailsType
      assertThat(details.apiLevel).isEqualTo(24)
      assertThat(details.isBaseExtension).isTrue()
    }
  }

  @Test
  fun testAddonV1() {
    unmarshalAndVerify("addon/v1/addon.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.AddonDetailsType
      assertThat(details.apiLevel).isEqualTo(22)
      assertThat(details.vendor.id).isEqualTo("google")
      assertThat(details.tag.id).isEqualTo("google_apis")
      assertThat(details.abis).containsExactly("armeabi")
    }
  }

  @Test
  fun testAddonV2() {
    unmarshalAndVerify("addon/v2/addon.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.AddonDetailsType
      assertThat(details.apiLevel).isEqualTo(22)
      assertThat(details.vendor.id).isEqualTo("google")
      assertThat(details.abis).containsExactly("armeabi")
    }
  }

  @Test
  fun testAddonV3() {
    unmarshalAndVerify("addon/v3/addon.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.AddonDetailsType
      assertThat(details.apiLevel).isEqualTo(22)
      assertThat(details.isBaseExtension).isTrue()
      assertThat(details.abis).containsExactly("armeabi")
    }
  }

  @Test
  fun testSysImgV1() {
    unmarshalAndVerify("sys-img/v1/sys-img.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.SysImgDetailsType
      assertThat(details.apiLevel).isEqualTo(22)
      assertThat(details.abis).containsExactly("x86")
      assertThat(details.tags.map { it.id }).containsExactly("google_apis")
    }
  }

  @Test
  fun testSysImgV2() {
    unmarshalAndVerify("sys-img/v2/sys-img.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.SysImgDetailsType
      assertThat(details.apiLevel).isEqualTo(22)
      assertThat(details.abis).containsExactly("x86")
      assertThat(details.tags.map { it.id }).containsExactly("google_apis", "tablet")
    }
  }

  @Test
  fun testSysImgV3() {
    unmarshalAndVerify("sys-img/v3/sys-img.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.SysImgDetailsType
      assertThat(details.apiLevel).isEqualTo(22)
      assertThat(details.isBaseExtension).isTrue()
      assertThat(details.abis).containsExactly("x86")
      assertThat(details.tags.map { it.id }).containsExactly("google_apis")
    }
  }

  @Test
  fun testSysImgV4() {
    unmarshalAndVerify("sys-img/v4/sys-img.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.SysImgDetailsType
      assertThat(details.apiLevel).isEqualTo(22)
      assertThat(details.abis).containsExactly("x86", "armeabi-v7a").inOrder()
      assertThat(details.tags.map { it.id }).containsExactly("google_apis", "wear").inOrder()
      assertThat(details.translatedAbis).containsExactly("riscv64")
    }
  }

  @Test
  fun testSysImgV4MinorApi() {
    unmarshalAndVerify("sys-img/v4/minor_api.xml") { pkg ->
      val details = pkg.typeDetails as DetailsTypes.SysImgDetailsType
      assertThat(details.apiLevel).isEqualTo(36)
      assertThat(details.apiMinorLevel).isEqualTo(1)
      assertThat(details.androidVersion).isEqualTo(AndroidVersion(36, 1, null, null, true))
      assertThat(details.abis).containsExactly("x86")
      assertThat(details.tags.map { it.id }).containsExactly("google_apis")
    }
  }

  private fun unmarshalAndVerify(relativePath: String, verification: (LocalPackage) -> Unit) {
    val path = "/com/android/sdklib/repository/testdata/$relativePath"
    val inputStream = this::class.java.getResourceAsStream(path)
    assertWithMessage("Resource at $path").that(inputStream).isNotNull()

    val repo = SchemaModuleUtil.unmarshal(inputStream, modules, true, progress, relativePath) as Repository
    progress.assertNoErrorsOrWarnings()
    verification(repo.localPackage!!)
  }
}
