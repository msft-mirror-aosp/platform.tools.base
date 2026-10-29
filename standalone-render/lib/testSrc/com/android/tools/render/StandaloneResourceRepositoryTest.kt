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

package com.android.tools.render

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.resources.ResourceType
import com.android.tools.res.FolderResourceRepository
import com.android.tools.res.LocalResourceRepository
import java.io.File
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StandaloneResourceRepositoryTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun testGetNamespaces_includesApkNamespaces() {
    val apkRepo = LocalResourceRepository.EmptyRepository<Path>(ResourceNamespace.ANDROID)
    val repo = StandaloneResourceRepository(emptyList(), apkRepo)

    assertTrue(repo.namespaces.contains(ResourceNamespace.ANDROID))
  }

  @Test
  fun testGetResources_folderResourcesTakePrecedenceOverApkResources() {
    val liveResDir = temporaryFolder.newFolder("live_res")
    File(liveResDir, "values")
      .apply { mkdirs() }
      .resolve("strings.xml")
      .writeText(
        """
        <resources>
            <string name="greeting">Live Greeting</string>
            <string name="live_only">Live Only</string>
        </resources>
        """
          .trimIndent()
      )

    val staleApkResDir = temporaryFolder.newFolder("stale_apk_res")
    File(staleApkResDir, "values")
      .apply { mkdirs() }
      .resolve("strings.xml")
      .writeText(
        """
        <resources>
            <string name="greeting">Stale APK Greeting</string>
            <string name="apk_only">APK Only</string>
        </resources>
        """
          .trimIndent()
      )

    val apkRepo = FolderResourceRepository(staleApkResDir)
    val repo = StandaloneResourceRepository(listOf(liveResDir.absolutePath), apkRepo)

    val greetingItems = repo.getResources(ResourceNamespace.RES_AUTO, ResourceType.STRING, "greeting")
    assertEquals(1, greetingItems.size)
    assertEquals("Live Greeting", greetingItems.single().resourceValue?.value)

    val liveOnlyItems = repo.getResources(ResourceNamespace.RES_AUTO, ResourceType.STRING, "live_only")
    assertEquals(1, liveOnlyItems.size)
    assertEquals("Live Only", liveOnlyItems.single().resourceValue?.value)

    val apkOnlyItems = repo.getResources(ResourceNamespace.RES_AUTO, ResourceType.STRING, "apk_only")
    assertEquals(1, apkOnlyItems.size)
    assertEquals("APK Only", apkOnlyItems.single().resourceValue?.value)
  }
}
