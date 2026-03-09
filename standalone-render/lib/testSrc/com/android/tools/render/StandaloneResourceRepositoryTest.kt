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
import com.android.tools.res.LocalResourceRepository
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneResourceRepositoryTest {

  @Test
  fun testGetNamespaces_includesApkNamespaces() {
    val apkRepo = LocalResourceRepository.EmptyRepository<Path>(ResourceNamespace.ANDROID)
    val repo = StandaloneResourceRepository(emptyList(), apkRepo)

    assertTrue(repo.namespaces.contains(ResourceNamespace.ANDROID))
  }
}
