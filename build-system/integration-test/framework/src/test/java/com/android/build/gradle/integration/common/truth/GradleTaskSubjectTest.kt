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

import com.android.build.gradle.integration.common.output.assert
import com.google.common.truth.ExpectFailure
import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.PluginIdentifier
import org.gradle.tooling.events.task.TaskOperationDescriptor
import org.gradle.tooling.events.task.TaskProgressEvent
import org.gradle.tooling.events.task.internal.DefaultTaskFailureResult
import org.gradle.tooling.events.task.internal.DefaultTaskFinishEvent
import org.gradle.tooling.events.task.internal.DefaultTaskSkippedResult
import org.gradle.tooling.events.task.internal.DefaultTaskSuccessResult
import org.gradle.tooling.events.task.internal.TaskExecutionDetails
import org.jetbrains.annotations.CheckReturnValue
import org.junit.Test
import java.util.*

@Suppress("UnstableApiUsage")
class GradleTaskSubjectTest {

    private val taskStateList: TaskStateList

    init {
        val fakeGradleOutput = """
            :updateToDateTask UP-TO-DATE

            :fromCacheTask FROM-CACHE

            :didWorkTask (Thread[Daemon worker,5,main]) started.
            :didWorkTask
            Executing task ':didWorkTask' (up-to-date check took 0.0 secs) due to:
              No history is available.
            :didWorkTask (Thread[Daemon worker,5,main]) completed. Took 0.004 secs.

            :skippedTask SKIPPED

            :fromCacheTask FAILED
        """.trimIndent()

        val events = buildList {
            add(upToDate(":upToDateTask"))
            add(fromCache(":fromCacheTask"))
            add(didWork(":didWorkTask", true))
            add(didWork(":didWorkTask", false))
            add(skipped(":skippedTask"))
            add(failed(":failedTask"))
        }

        taskStateList = TaskStateList(events, Scanner(fakeGradleOutput))
    }

    @Test
    fun wasUpToDate() {
        asserTask(":upToDateTask").wasUpToDate()

        expectFailureWhenTestingThat(":didWorkTask") {
            wasUpToDate()
        }.assert {
            factKeys().containsExactly("gradleTask was", "expected to be true", "value of")
            factValue("value of").isEqualTo("gradleTask.named(:didWorkTask).wasUpToDate()")
            factValue("gradleTask was").isEqualTo("TaskInfo(taskName=':didWorkTask', executionState=DID_WORK)")
        }
    }

    @Test
    fun wasFromCache() {
        asserTask(":fromCacheTask").wasFromCache()

        expectFailureWhenTestingThat(":didWorkTask") {
            wasFromCache()
        }.assert {
            factKeys().containsExactly("gradleTask was", "expected to be true", "value of")
            factValue("value of").isEqualTo("gradleTask.named(:didWorkTask).wasFromCache()")
            factValue("gradleTask was").isEqualTo("TaskInfo(taskName=':didWorkTask', executionState=DID_WORK)")
        }
    }

    @Test
    fun didWork() {
        asserTask(":didWorkTask").didWork()

        expectFailureWhenTestingThat(":upToDateTask") {
            didWork()
        }.assert {
            factKeys().containsExactly("gradleTask was", "expected to be true", "value of")
            factValue("value of").isEqualTo("gradleTask.named(:upToDateTask).didWork()")
            factValue("gradleTask was").isEqualTo("TaskInfo(taskName=':upToDateTask', executionState=UP_TO_DATE)")
        }
    }

    @Test
    fun wasSkipped() {
        asserTask(":skippedTask").wasSkipped()

        expectFailureWhenTestingThat(":didWorkTask") {
            wasSkipped()
        }.assert {
            factKeys().containsExactly("gradleTask was", "expected to be true", "value of")
            factValue("value of").isEqualTo("gradleTask.named(:didWorkTask).wasSkipped()")
            factValue("gradleTask was").isEqualTo("TaskInfo(taskName=':didWorkTask', executionState=DID_WORK)")
        }
    }

    @Test
    fun failed() {
        asserTask(":failedTask").failed()

        expectFailureWhenTestingThat(":didWorkTask") {
            failed()
        }.assert {
            factKeys().containsExactly("gradleTask was", "expected to be true", "value of")
            factValue("value of").isEqualTo("gradleTask.named(:didWorkTask).failed()")
            factValue("gradleTask was").isEqualTo("TaskInfo(taskName=':didWorkTask', executionState=DID_WORK)")
        }
    }

    @Test
    fun ranBefore() {
        asserTask(":upToDateTask").ranBefore(":didWorkTask")

        expectFailureWhenTestingThat(":didWorkTask") {
            ranBefore(":upToDateTask")
        }.assert {
            factKeys().containsExactly("gradleTask was", "expected to be true", "value of")
            factValue("value of").isEqualTo("gradleTask.named(:didWorkTask).ranBefore(:upToDateTask)")
            factValue("gradleTask was").isEqualTo("TaskInfo(taskName=':didWorkTask', executionState=DID_WORK)")
        }
    }

    @Test
    fun ranAfter() {
        asserTask(":didWorkTask").ranAfter(":upToDateTask")

        expectFailureWhenTestingThat(":upToDateTask") {
            ranAfter(":didWorkTask")
        }.assert {
            factKeys().containsExactly("gradleTask was", "expected to be true", "value of")
            factValue("value of").isEqualTo("gradleTask.named(:upToDateTask).ranAfter(:didWorkTask)")
            factValue("gradleTask was").isEqualTo("TaskInfo(taskName=':upToDateTask', executionState=UP_TO_DATE)")
        }
    }

    private fun asserTask(name: String): GradleTaskSubject = taskStateList.assertTask(name)

    @CheckReturnValue
    private fun expectFailureWhenTestingThat(taskName: String, action: GradleTaskSubject.() -> Unit): AssertionError {
        return ExpectFailure.expectFailureAbout(GradleTaskSubject.executionStates()) {
            action(it.that(taskStateList.findTask(taskName)))
        }
    }

    private class FakeTaskOperationDescriptor(
        private val name: String
    ) : TaskOperationDescriptor, OperationDescriptor {
        override fun getTaskPath(): String {
            return name
        }

        override fun getDependencies(): MutableSet<out OperationDescriptor?>? {
            return null
        }

        override fun getOriginPlugin(): PluginIdentifier? {
            return null
        }

        override fun getDisplayName(): String {
            return "Task$name"
        }

        override fun getName(): String {
            return name
        }

        override fun getParent(): OperationDescriptor? {
            return null
        }
    }

    companion object {
        private fun upToDate(name: String): TaskProgressEvent {
            return DefaultTaskFinishEvent(
                /* eventTime = */ 0,
                /* displayName = */ "Task :$name UP-TO-DATE",
                /* descriptor = */ FakeTaskOperationDescriptor(name),
                /* result = */ DefaultTaskSuccessResult(0, 5, true, false, null)
            )
        }

        private fun fromCache(name: String): TaskProgressEvent {
            // When the task's output is retrieved from the cache, Gradle returns both upToDate() and
            // fromCache() as true (see https://github.com/gradle/gradle/issues/5252).
            return DefaultTaskFinishEvent(
                /* eventTime = */ 0,
                /* displayName = */ "Task :$name FROM_CACHE",
                /* descriptor = */ FakeTaskOperationDescriptor(name),
                /* result = */ DefaultTaskSuccessResult(0, 5, true, true, null)
            )
        }

        private fun didWork(name: String, isIncremental: Boolean): TaskProgressEvent {
            return DefaultTaskFinishEvent(
                /* eventTime = */ 0,
                /* displayName = */ "Task :$name SUCCESS",
                /* descriptor = */ FakeTaskOperationDescriptor(name),
                /* result = */ DefaultTaskSuccessResult(
                    /* startTime = */ 0,
                    /* endTime = */ 5,
                    /* upToDate = */ false,
                    /* fromCache = */ false,
                    /* taskExecutionDetails = */ TaskExecutionDetails.of(isIncremental, ArrayList())
                )
            )
        }

        private fun skipped(name: String): TaskProgressEvent {
            return DefaultTaskFinishEvent(
                /* eventTime = */ 0,
                /* displayName = */ "Task :$name SKIPPED",
                /* descriptor = */ FakeTaskOperationDescriptor(name),
                /* result = */ DefaultTaskSkippedResult(0, 5, "SKIPPED")
            )
        }

        private fun failed(name: String): TaskProgressEvent {
            return DefaultTaskFinishEvent(
                /* eventTime = */ 0,
                /* displayName = */ "Task :$name FAILED",
                /* descriptor = */ FakeTaskOperationDescriptor(name),
                /* result = */ DefaultTaskFailureResult(0, 5, null, null)
            )
        }
    }
}
