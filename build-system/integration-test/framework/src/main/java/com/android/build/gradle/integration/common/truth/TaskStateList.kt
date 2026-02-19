/*
 * Copyright (C) 2016 The Android Open Source Project
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

import com.android.build.gradle.internal.profile.getTaskState
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.truth.Truth
import com.google.wireless.android.sdk.stats.GradleTaskExecution
import java.util.EnumMap
import java.util.Scanner
import java.util.regex.Pattern
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskOperationResult

/** List of the task state for a build. */
class TaskStateList(progressEvents: List<ProgressEvent>, gradleOutput: Scanner) {
  /** State of a task during a build. These states are mutually exclusive. */
  enum class ExecutionState {
    UP_TO_DATE,
    FROM_CACHE,
    DID_WORK,
    SKIPPED,
    FAILED,
  }

  class TaskInfo(
    @JvmField val taskName: String,
    @JvmField @get:Suppress("unused") val executionState: ExecutionState,
    @JvmField val taskStateList: TaskStateList,
  ) {
    fun wasUpToDate(): Boolean {
      return executionState == ExecutionState.UP_TO_DATE
    }

    fun wasFromCache(): Boolean {
      return executionState == ExecutionState.FROM_CACHE
    }

    fun didWork(): Boolean {
      return executionState == ExecutionState.DID_WORK
    }

    fun wasSkipped(): Boolean {
      return executionState == ExecutionState.SKIPPED
    }

    fun failed(): Boolean {
      return executionState == ExecutionState.FAILED
    }

    override fun toString(): String {
      return "TaskInfo(taskName='$taskName', executionState=$executionState)"
    }
  }

  /**
   * the list of tasks in the order that they were output by the Gradle event.
   *
   * This is important for testing task ordering.
   */
  private val orderedTaskList: List<String>
  private val taskInfoMap: Map<String, TaskInfo>
  private val taskStateMap: Map<ExecutionState, Set<String>>

  init {
    val taskListBuilder = ImmutableList.builder<String>()
    val taskMap: MutableMap<ExecutionState, MutableSet<String>> = EnumMap(ExecutionState::class.java)
    for (state in ExecutionState.entries) {
      taskMap.put(state, HashSet<String>())
    }

    for (progressEvent in progressEvents) {
      if (progressEvent is TaskFinishEvent) {
        val task = progressEvent.descriptor.name
        taskListBuilder.add(task)
        val taskState: ExecutionState = getTaskState(progressEvent.result)
        taskMap[taskState]!!.add(task)
      }
    }

    orderedTaskList = taskListBuilder.build()

    // Among the tasks that did work, detect those that were skipped and correct their state to
    // SKIPPED. (For "anchor" tasks such as "build", "check", Gradle does not report them with
    // TaskSkippedResult, so we need to detect them in the Gradle output.)
    val noActionsTasks: ImmutableSet<String> = getTasksByPatternFromGradleOutput(gradleOutput, NO_ACTIONS_PATTERN)
    Preconditions.checkState(orderedTaskList.containsAll(noActionsTasks))
    for (noActionTask in noActionsTasks) {
      if (taskMap[ExecutionState.DID_WORK]!!.contains(noActionTask!!)) {
        taskMap[ExecutionState.DID_WORK]!!.remove(noActionTask)
        taskMap[ExecutionState.SKIPPED]!!.add(noActionTask)
      }
    }

    val taskInfoMapBuilder = ImmutableMap.builder<String, TaskInfo>()
    for (state in taskMap.keys) {
      for (task in taskMap[state]!!) {
        taskInfoMapBuilder.put(task, TaskInfo(task, state, this))
      }
    }
    taskInfoMap = taskInfoMapBuilder.build()

    val taskStateMapBuilder = ImmutableMap.builder<ExecutionState?, Set<String>>()
    for (state in ExecutionState.entries) {
      taskStateMapBuilder.put(state, ImmutableSet.copyOf<String>(taskMap[state]))
    }
    taskStateMap = taskStateMapBuilder.build()
  }

  fun findTask(task: String): TaskInfo? {
    return taskInfoMap[task]
  }

  fun getTask(task: String): TaskInfo {
    return Preconditions.checkNotNull<TaskInfo>(taskInfoMap[task], "Task %s not found", task)
  }

  val tasks: List<String>
    get() = orderedTaskList

  val taskStates: Map<String, ExecutionState>
    get() {
      val taskStates: MutableMap<String, ExecutionState> = HashMap<String, ExecutionState>()
      for (entry in taskInfoMap.entries) {
        taskStates.put(entry.key, entry.value.executionState)
      }
      return taskStates
    }

  val upToDateTasks: Set<String>
    get() = taskStateMap[ExecutionState.UP_TO_DATE]!!

  val fromCacheTasks: Set<String>
    get() = taskStateMap[ExecutionState.FROM_CACHE]!!

  val didWorkTasks: Set<String>
    get() = taskStateMap[ExecutionState.DID_WORK]!!

  val skippedTasks: Set<String>
    get() = taskStateMap[ExecutionState.SKIPPED]!!

  val failedTasks: Set<String>
    get() = taskStateMap[ExecutionState.FAILED]!!

  fun getTaskIndex(taskName: String): Int {
    Preconditions.checkArgument(taskName.startsWith(":"), "Task name (\"" + taskName + "\") must start with ':'")
    Preconditions.checkArgument(orderedTaskList.contains(taskName), "Task %s not run", taskName)
    return orderedTaskList.indexOf(taskName)
  }

  companion object {
    val NO_ACTIONS_PATTERN: Pattern = Pattern.compile("Skipping task '(.*)' as it has no actions.")

    private fun getTaskState(taskOperationResult: TaskOperationResult): ExecutionState {
      val taskState = taskOperationResult.getTaskState()
      return when (taskState) {
        GradleTaskExecution.TaskState.UP_TO_DATE -> ExecutionState.UP_TO_DATE
        GradleTaskExecution.TaskState.FROM_CACHE -> ExecutionState.FROM_CACHE
        GradleTaskExecution.TaskState.DID_WORK_INCREMENTAL,
        GradleTaskExecution.TaskState.DID_WORK_NON_INCREMENTAL -> ExecutionState.DID_WORK
        GradleTaskExecution.TaskState.SKIPPED -> ExecutionState.SKIPPED
        GradleTaskExecution.TaskState.FAILED -> ExecutionState.FAILED
        else -> throw IllegalStateException("Task state is not yet handled: " + taskState)
      }
    }

    private fun getTasksByPatternFromGradleOutput(gradleOutput: Scanner, pattern: Pattern): ImmutableSet<String> {
      val result = ImmutableSet.builder<String>()
      try {
        while (gradleOutput.hasNextLine()) {
          val matcher = pattern.matcher(gradleOutput.nextLine())
          if (matcher.find()) {
            result.add(matcher.group(1))
          }
        }
      } finally {
        gradleOutput.close()
      }
      return result.build()
    }
  }

  internal fun assertTask(name: String, withInfo: String? = null): GradleTaskSubject {
    Preconditions.checkArgument(name.startsWith(":"), "Task name must start with :")

    // let's do a full check on the task presence. This will output a nicer error
    // than just doing a null check on the findTask result
    Truth.assertThat(tasks).contains(name)

    return GradleTaskSubject.assertThat(findTask(name)!!, withInfo)
  }
}
