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

package com.android.build.gradle.internal.api

import com.android.build.api.dsl.AgpTestSuiteDependencies
import com.android.build.gradle.internal.services.VariantServices
import com.android.build.gradle.internal.services.VariantServicesImpl
import com.android.build.gradle.internal.services.createProjectServices
import com.android.build.gradle.internal.testsuites.impl.AssetsTestSuiteSourceSet
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.quality.Strictness

class AssetsTestSuiteSourceSetTest {
  @get:Rule val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

  @get:Rule val tmpFolder: TemporaryFolder = TemporaryFolder()

  @Mock lateinit var dependencies: AgpTestSuiteDependencies

  lateinit var variantServices: VariantServices
  lateinit var project: Project

  @Before
  fun init() {
    project = ProjectBuilder.builder().withProjectDir(tmpFolder.newFolder()).build()
    variantServices = VariantServicesImpl(createProjectServices(project), forUnitTesting = true)
  }

  @Test
  fun testDefaultSourceSet() {
    val sourceSet =
      AssetsTestSuiteSourceSet(
        sourceSetName = "assetsSuite",
        testSuiteName = "assetsSuite",
        isMixed = false,
        variantServices = variantServices,
        dependencies = dependencies,
      )
    Truth.assertThat(sourceSet.get().all.get()).containsExactly(project.layout.projectDirectory.dir("src/assetsSuite"))
  }

  @Test
  fun testIsMixedSourceSet() {
    val sourceSet =
      AssetsTestSuiteSourceSet(
        sourceSetName = "backupTestAssets",
        testSuiteName = "backupTest",
        isMixed = true,
        variantServices = variantServices,
        dependencies = dependencies,
      )
    Truth.assertThat(sourceSet.get().all.get()).containsExactly(project.layout.projectDirectory.dir("src/backupTest/assetsTest"))
  }
}
