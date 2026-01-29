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

package com.android.build.gradle.integration.common.fixture.project

import com.android.build.gradle.integration.common.fixture.project.GradleRule.Companion.configure
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.LocalTestProjectSpec

/**
 * Entry point for creating a GradleRule.
 *
 * This can be queried from the [GradleRule] companion class or via [GradleRule.configure] (which returns [GradleRuleBuilder])
 */
interface GradleRuleEntryPoint {
  /**
   * Returns a [GradleRule] for a synthetic project configured with the [GradleBuildDefinition].
   *
   * To configure the rule, use [configure] instead
   *
   * @param folderName the name of the folder containing the build.
   * @param logicalName The logical name of the build in gradle. This impact the groupId information of the subprojects. if null, same as
   *   folder name
   * @param configAction the action to configure the build
   */
  fun from(
    folderName: String = GradleBuildDefinition.DEFAULT_BUILD_NAME,
    logicalName: String? = null,
    configAction: GradleBuildDefinition.() -> Unit,
  ): GradleRule

  /**
   * Returns a [GradleRule] for a project configured from both an on-disk test project and a [GradleBuildDefinition].
   *
   * To configure the rule, use [configure] instead
   *
   * @param testProjectName the name of the on-disk test project.
   * @param folderName the name of the folder containing the build.
   * @param logicalName The logical name of the build in gradle. This impact the groupId information of the subprojects. if null, same as
   *   folder name
   * @param configAction the action to configure the build
   */
  fun fromProject(
    testProjectName: String,
    folderName: String = GradleBuildDefinition.DEFAULT_BUILD_NAME,
    logicalName: String? = null,
    configAction: GradleBuildDefinition.() -> Unit,
  ): GradleRule

  fun fromProject(
    testProjectSpec: LocalTestProjectSpec,
    folderName: String = GradleBuildDefinition.DEFAULT_BUILD_NAME,
    logicalName: String? = null,
    configAction: (GradleBuildDefinition.() -> Unit)? = null,
  ): GradleRule
}
