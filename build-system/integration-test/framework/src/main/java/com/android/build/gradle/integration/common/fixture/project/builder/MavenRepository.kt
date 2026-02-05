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

package com.android.build.gradle.integration.common.fixture.project.builder

import com.android.build.gradle.integration.common.dependencies.AarBuilder
import com.android.build.gradle.integration.common.dependencies.JarBuilder
import com.android.build.gradle.integration.common.dependencies.JarWithDependenciesBuilder
import com.android.testutils.MavenRepoGenerator.Library

/** Allows configuring a custom repository with test libraries. */
@GradleDefinitionDsl
interface MavenRepository {
  /** Adds a manually created library */
  fun library(library: Library)

  /**
   * Creates a JAR with the provided coordinates.
   *
   * This returns an [JarBuilder] which can be used to configure the Jar.
   */
  fun jar(mavenCoordinate: String): JarWithDependenciesBuilder

  /**
   * Creates an AAR.
   *
   * This returns an [AarBuilder] which can be used to configure the AAR.
   *
   * @param groupId the groupId of the artifact and is used for the library namespace
   * @param artifactId the artifactId. If null, the last segment of groupId is used
   * @param version the version. Defaults to 1.0
   */
  fun aar(groupId: String, artifactId: String? = null, version: String = "1.0"): AarBuilder
}
