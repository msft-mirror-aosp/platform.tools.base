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

class DefaultAndroidSourceSetTest {
  private lateinit var project: Project
  private lateinit var sourceSet: DefaultAndroidSourceSet

  @Before
  fun setUp() {
    project = ProjectFactory.project
    sourceSet = DefaultAndroidSourceSet("main", project, false)
  }

  @Test
  fun testAarKeepRulesInitialization() {
    assertThat(sourceSet.keepRules).isNotNull()
    assertThat(sourceSet.keepRules.name).isEqualTo("main keepRules")
    // Check default path
    val expectedPath = "src/main/keepRules"
    assertThat(sourceSet.keepRules.directories).containsExactly(expectedPath)
  }

  @Test
  fun testSetRootUpdatesAarKeepRules() {
    sourceSet.setRoot("newRoot")
    val expectedPath = "newRoot/keepRules"
    assertThat(sourceSet.keepRules.directories).contains(expectedPath)
  }

  @Test
  fun testAarKeepRulesFilter() {
    val filter = (sourceSet.keepRules as DefaultAndroidSourceDirectorySet).filter
    assertThat(filter.includes).containsExactly("**/*.keep")
  }

  @Test
  fun testAarKeepRulesAction() {
    var called = false
    sourceSet.keepRules {
      called = true
      assertThat(this).isEqualTo(sourceSet.keepRules)
    }
    assertThat(called).isTrue()
  }
}
