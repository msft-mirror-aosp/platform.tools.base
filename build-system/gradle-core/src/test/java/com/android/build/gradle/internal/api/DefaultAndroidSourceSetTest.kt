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

  @Test
  fun testStringsAreInternedAcrossSourceSets() {
    val first = DefaultAndroidSourceSet(StringBuilder("test").append("Debug").toString(), project, false)
    val second = DefaultAndroidSourceSet(StringBuilder("test").append("Debug").toString(), project, false)

    assertThat(first.name).isSameInstanceAs(second.name)
    assertThat(first.manifest.name).isSameInstanceAs(second.manifest.name)
    assertThat(first.manifest.toString()).isSameInstanceAs(second.manifest.toString())

    assertThat(first.apiConfigurationName).isSameInstanceAs(second.apiConfigurationName)
    assertThat(first.compileOnlyConfigurationName).isSameInstanceAs(second.compileOnlyConfigurationName)
    assertThat(first.compileOnlyApiConfigurationName).isSameInstanceAs(second.compileOnlyApiConfigurationName)
    assertThat(first.implementationConfigurationName).isSameInstanceAs(second.implementationConfigurationName)
    assertThat(first.runtimeOnlyConfigurationName).isSameInstanceAs(second.runtimeOnlyConfigurationName)
    assertThat(first.annotationProcessorConfigurationName).isSameInstanceAs(second.annotationProcessorConfigurationName)
    assertThat(first.kaptConfigurationName).isSameInstanceAs(second.kaptConfigurationName)
    assertThat(first.packageConfigurationName).isSameInstanceAs(second.packageConfigurationName)

    assertThat(first.java.directories.single()).isSameInstanceAs(second.java.directories.single())
    assertThat(first.kotlin.directories.toList()[0]).isSameInstanceAs(second.kotlin.directories.toList()[0])
    assertThat(first.kotlin.directories.toList()[1]).isSameInstanceAs(second.kotlin.directories.toList()[1])
    assertThat(first.resources.directories.single()).isSameInstanceAs(second.resources.directories.single())
    assertThat(first.res.directories.single()).isSameInstanceAs(second.res.directories.single())
    assertThat(first.assets.directories.single()).isSameInstanceAs(second.assets.directories.single())
    assertThat(first.aidl.directories.single()).isSameInstanceAs(second.aidl.directories.single())
    assertThat(first.renderscript.directories.single()).isSameInstanceAs(second.renderscript.directories.single())
    assertThat(first.baselineProfiles.directories.single()).isSameInstanceAs(second.baselineProfiles.directories.single())
    assertThat(first.keepRules.directories.single()).isSameInstanceAs(second.keepRules.directories.single())
    assertThat(first.jniLibs.directories.single()).isSameInstanceAs(second.jniLibs.directories.single())
    assertThat(first.shaders.directories.single()).isSameInstanceAs(second.shaders.directories.single())
    assertThat(first.mlModels.directories.single()).isSameInstanceAs(second.mlModels.directories.single())
    assertThat(first.extras.create("custom").directories.single()).isSameInstanceAs(second.extras.create("custom").directories.single())
  }
}
