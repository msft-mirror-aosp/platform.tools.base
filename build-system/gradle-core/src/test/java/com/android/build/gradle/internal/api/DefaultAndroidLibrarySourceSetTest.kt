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

package com.android.build.gradle.internal.api

import com.android.build.gradle.internal.fixtures.ProjectFactory
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.junit.Before
import org.junit.Test

class DefaultAndroidLibrarySourceSetTest {

  private lateinit var project: Project
  private lateinit var sourceSet: DefaultAndroidLibrarySourceSet

  @Before
  fun setUp() {
    project = ProjectFactory.project
    sourceSet = DefaultAndroidLibrarySourceSet("main", project, false)
  }

  @Test
  fun testAarKeepRulesInitialization() {
    assertThat(sourceSet.aarKeepRules).isNotNull()
    assertThat(sourceSet.aarKeepRules.name).isEqualTo("main aarKeepRules")

    // Check default path
    val expectedPath = "src/main/aarKeepRules"
    assertThat(sourceSet.aarKeepRules.directories).containsExactly(expectedPath)
  }

  @Test
  fun testSetRootUpdatesAarKeepRules() {
    sourceSet.setRoot("newRoot")

    val expectedPath = "newRoot/aarKeepRules"
    assertThat(sourceSet.aarKeepRules.directories).contains(expectedPath)
  }

  @Test
  fun testAarKeepRulesFilter() {
    val filter = (sourceSet.aarKeepRules as DefaultAndroidSourceDirectorySet).filter
    assertThat(filter.includes).containsExactly("**/*.keep")
  }

  @Test
  fun testAarKeepRulesAction() {
    var called = false
    sourceSet.aarKeepRules {
      called = true
      assertThat(this).isEqualTo(sourceSet.aarKeepRules)
    }
    assertThat(called).isTrue()
  }
}
