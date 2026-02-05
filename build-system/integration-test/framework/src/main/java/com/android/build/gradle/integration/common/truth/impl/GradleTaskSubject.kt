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

package com.android.build.gradle.integration.common.truth.impl

import com.android.build.gradle.integration.common.truth.TaskStateList
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject

/**
 * Implementation of the interface in a separate package so that it keeps the same class name so that Truth can be happy inferring the
 * object name from the impl name.
 */
internal class GradleTaskSubject(metadata: FailureMetadata, actual: TaskStateList.TaskInfo, private val info: String?) :
  Subject<GradleTaskSubject, TaskStateList.TaskInfo>(metadata, actual),
  com.android.build.gradle.integration.common.truth.GradleTaskSubject {

  override fun hasState(state: TaskStateList.ExecutionState?) {
    check("${checkPrefix()}.state()").that(actual().executionState).isEqualTo(state)
  }

  override fun wasUpToDate() {
    check("${checkPrefix()}.wasUpToDate()").that(actual().executionState == TaskStateList.ExecutionState.UP_TO_DATE).isTrue()
  }

  override fun wasFromCache() {
    check("${checkPrefix()}.wasFromCache()").that(actual().executionState == TaskStateList.ExecutionState.FROM_CACHE).isTrue()
  }

  override fun didWork() {
    check("${checkPrefix()}.didWork()").that(actual().executionState == TaskStateList.ExecutionState.DID_WORK).isTrue()
  }

  override fun wasSkipped() {
    check("${checkPrefix()}.wasSkipped()").that(actual().executionState == TaskStateList.ExecutionState.SKIPPED).isTrue()
  }

  override fun failed() {
    check("${checkPrefix()}.failed()").that(actual().executionState == TaskStateList.ExecutionState.FAILED).isTrue()
  }

  override fun ranBefore(taskName: String) {
    val taskStateList = actual().taskStateList

    check("${checkPrefix()}.ranBefore($taskName)")
      .that(taskStateList.getTaskIndex(actual().taskName) <= taskStateList.getTaskIndex(taskName))
      .isTrue()
  }

  override fun ranAfter(taskName: String) {
    val taskStateList = actual().taskStateList

    check("${checkPrefix()}.ranAfter($taskName)")
      .that(taskStateList.getTaskIndex(actual().taskName) >= taskStateList.getTaskIndex(taskName))
      .isTrue()
  }

  private fun checkPrefix(): String {
    return "named(${actual().taskName}${computeInfoString()})"
  }

  private fun computeInfoString(): String {
    return info?.let { ".message($it)" } ?: ""
  }
}
