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

package com.android.build.gradle.integration.common.fixture.project.builder

/**
 * an object that can describe and configure an on-disk test projects.
 *
 * To be used with [GradleRule.fromProject]
 */
interface LocalTestProjectSpec {

  /**
   * the name of the on-disk project.
   *
   * This is the name of the folder containing the project
   */
  val projectName: String

  /** An action to configure the project */
  val configAction: GradleBuildDefinition.() -> Unit
}
