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

package com.android.build.gradle.integration.common.truth

import com.android.build.gradle.integration.common.truth.impl.GradleTaskSubject as GradleTaskSubjectImpl
import com.google.common.truth.Subject.Factory
import com.google.common.truth.Truth

/**
 * a Truth subject to validate the execution outcome of a Gadle Task.
 *
 * This is accessed via [com.android.build.gradle.integration.common.fixture.GradleBuildResult.assertTask]
 */
interface GradleTaskSubject {
  companion object {
    internal fun assertThat(taskInfo: TaskStateList.TaskInfo, withInfo: String? = null): GradleTaskSubject =
      Truth.assertAbout(executionStates(withInfo)).that(taskInfo)

    internal fun executionStates(info: String? = null): Factory<GradleTaskSubjectImpl, TaskStateList.TaskInfo> {
      return Factory<GradleTaskSubjectImpl, TaskStateList.TaskInfo> { metadata, actual -> GradleTaskSubjectImpl(metadata, actual, info) }
    }
  }

  fun hasState(state: TaskStateList.ExecutionState?)

  fun wasUpToDate()

  fun wasFromCache()

  fun didWork()

  fun wasSkipped()

  fun failed()

  fun ranBefore(taskName: String)

  fun ranAfter(taskName: String)
}
