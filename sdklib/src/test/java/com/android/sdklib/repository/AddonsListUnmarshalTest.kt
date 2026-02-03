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

import com.android.repository.api.RepoManager
import com.android.repository.api.SchemaModule
import com.android.repository.impl.meta.SchemaModuleUtil
import com.android.repository.impl.sources.RemoteListSourceProviderImpl
import com.android.repository.testframework.FakeProgressIndicator
import com.android.sdklib.repository.sources.RemoteSiteType
import com.google.common.collect.ImmutableSet
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class AddonsListUnmarshalTest {

  private val progress = FakeProgressIndicator()

  // Schema module for the common site list
  private val commonAddonListModule =
    SchemaModule<Any>(
      RemoteListSourceProviderImpl::class.java.getPackage().name + ".generated.v%d.ObjectFactory",
      "repo-sites-common-%d.xsd",
      RepoManager::class.java,
    )

  // Schema module for the sdk-specific site list
  private val sdkAddonListModule =
    SchemaModule<Any>(
      "com.android.sdklib.repository.sources.generated.v%d.ObjectFactory",
      "/xsd/sources/sdk-sites-list-%d.xsd",
      RemoteSiteType::class.java,
    )

  private val modules = ImmutableSet.of<SchemaModule<*>>(commonAddonListModule, sdkAddonListModule)

  @Test
  fun testAddonsListV1() {
    unmarshalAndVerify("addons-list/v1/addons.xml") { list ->
      val sites = list.site
      assertThat(sites).hasSize(4)
      assertThat(sites[0].url).isEqualTo("http://www.example.com/my_addons.xml")
      assertThat(sites[0].displayName).isEqualTo("My Example Add-ons.")
      assertThat(sites[1].url).isEqualTo("http://www.example.co.jp/addons.xml")
      assertThat(sites[1].displayName).isEqualTo("ありがとうございます。")
      assertThat(sites[2].url).isEqualTo("http://www.example.com/")
      assertThat(sites[3].url).isEqualTo("relative_url.xml")

      sites.forEach { assertThat(it).isInstanceOf(RemoteSiteType.AddonSiteType::class.java) }
    }
  }

  @Test
  fun testAddonsListV2() {
    unmarshalAndVerify("addons-list/v2/addons.xml") { list ->
      val sites = list.site
      assertThat(sites).hasSize(6)

      val addonSites = sites.filterIsInstance<RemoteSiteType.AddonSiteType>()
      val sysImgSites = sites.filterIsInstance<RemoteSiteType.SysImgSiteType>()

      assertThat(addonSites).hasSize(4)
      assertThat(sysImgSites).hasSize(2)

      assertThat(sysImgSites[0].displayName).isEqualTo("Example of sys-img URL using the default xml filename.")
      assertThat(sysImgSites[1].url).isEqualTo("http://www.example.com/specific_file.xml")
    }
  }

  @Test
  fun testAddonsListV3() {
    unmarshalAndVerify("addons-list/v3/addons.xml") { list -> verifyV3Plus(list) }
  }

  @Test
  fun testAddonsListV4() {
    unmarshalAndVerify("addons-list/v4/addons.xml") { list -> verifyV3Plus(list) }
  }

  @Test
  fun testAddonsListV5() {
    unmarshalAndVerify("addons-list/v5/addons.xml") { list -> verifyV3Plus(list) }
  }

  @Test
  fun testAddonsListV6() {
    unmarshalAndVerify("addons-list/v6/addons.xml") { list -> verifyV3Plus(list) }
  }

  @Test
  fun testAddonsListV7() {
    unmarshalAndVerify("addons-list/v7/addons.xml") { list -> verifyV3Plus(list) }
  }

  private fun verifyV3Plus(list: RemoteListSourceProviderImpl.SiteList) {
    val sites = list.site
    assertThat(sites).hasSize(6)
    val addonSites = sites.filterIsInstance<RemoteSiteType.AddonSiteType>()
    val sysImgSites = sites.filterIsInstance<RemoteSiteType.SysImgSiteType>()

    assertThat(addonSites).hasSize(4)
    assertThat(sysImgSites).hasSize(2)

    assertThat(addonSites[0].url).isEqualTo("http://www.example.com/my_addons2.xml")
    assertThat(sysImgSites[0].displayName).isEqualTo("Example of sys-img URL using the default xml filename.")
  }

  private fun unmarshalAndVerify(relativePath: String, verification: (RemoteListSourceProviderImpl.SiteList) -> Unit) {
    val path = "/com/android/sdklib/repository/testdata/$relativePath"
    val inputStream = this::class.java.getResourceAsStream(path)
    assertWithMessage("Resource at $path").that(inputStream).isNotNull()

    val list = SchemaModuleUtil.unmarshal(inputStream, modules, true, progress, relativePath) as RemoteListSourceProviderImpl.SiteList
    progress.assertNoErrorsOrWarnings()
    verification(list)
  }
}
