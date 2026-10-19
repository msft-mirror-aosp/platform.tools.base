/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.build.gradle.internal.services

import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.ProjectOptions
import com.android.build.gradle.options.StringOption.LINT_HEAP_SIZE
import com.android.build.gradle.options.StringOption.LINT_RESERVED_MEMORY_PER_TASK
import com.google.common.truth.Truth.assertThat
import kotlin.test.fail
import org.gradle.api.internal.provider.Providers
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Unit tests for [LintParallelBuildService] */
class LintParallelBuildServiceTest {
  private val projectOptions: ProjectOptions = mock()

  @Test
  fun testCalculateMaxParallelUsagesInProcess() {
    whenever(projectOptions.get(BooleanOption.RUN_LINT_IN_PROCESS)).thenReturn(true)
    whenever(projectOptions.getProvider(BooleanOption.RUN_LINT_IN_PROCESS)).thenReturn(Providers.of(true))

    // Check normal case
    assertThat(
        LintParallelBuildService.calculateMaxParallelUsages(
          projectOptions,
          runInProcess = true,
          maxRuntimeMemory = 20 * GB,
          totalPhysicalMemory = 40 * GB,
        )
      )
      .isEqualTo(30)

    // Check with specified LINT_RESERVED_MEMORY_PER_TASK
    whenever(projectOptions.get(LINT_RESERVED_MEMORY_PER_TASK)).thenReturn("1g")
    assertThat(
        LintParallelBuildService.calculateMaxParallelUsages(
          projectOptions,
          runInProcess = true,
          maxRuntimeMemory = 20 * GB,
          totalPhysicalMemory = 40 * GB,
        )
      )
      .isEqualTo(15)

    // Check case when there's not enough memory, but should still return 1
    assertThat(
        LintParallelBuildService.calculateMaxParallelUsages(
          projectOptions,
          runInProcess = true,
          maxRuntimeMemory = 0,
          totalPhysicalMemory = 40 * GB,
        )
      )
      .isEqualTo(1)

    // Check case when user specifies invalid LINT_RESERVED_MEMORY_PER_TASK
    whenever(projectOptions.get(LINT_RESERVED_MEMORY_PER_TASK)).thenReturn("invalid")
    try {
      LintParallelBuildService.calculateMaxParallelUsages(
        projectOptions,
        runInProcess = true,
        maxRuntimeMemory = 1 * GB,
        totalPhysicalMemory = 1 * GB,
      )
      fail("expected RuntimeException")
    } catch (e: RuntimeException) {
      assertThat(e.message).isEqualTo("Failed to parse ${LINT_RESERVED_MEMORY_PER_TASK.propertyName} \"invalid\".")
    }
  }

  @Test
  fun testCalculateMaxParallelUsagesOutOfProcess() {
    whenever(projectOptions.get(BooleanOption.RUN_LINT_IN_PROCESS)).thenReturn(false)
    whenever(projectOptions.getProvider(BooleanOption.RUN_LINT_IN_PROCESS)).thenReturn(Providers.of(false))

    // Check no specified lint heap size
    whenever(projectOptions.get(LINT_HEAP_SIZE)).thenReturn(null)
    assertThat(
        LintParallelBuildService.calculateMaxParallelUsages(
          projectOptions,
          runInProcess = false,
          maxRuntimeMemory = 10 * GB,
          totalPhysicalMemory = 40 * GB,
        )
      )
      .isEqualTo(2)

    // Check with specified lint heap size
    whenever(projectOptions.get(LINT_HEAP_SIZE)).thenReturn("2g")
    assertThat(
        LintParallelBuildService.calculateMaxParallelUsages(
          projectOptions,
          runInProcess = false,
          maxRuntimeMemory = 10 * GB,
          totalPhysicalMemory = 40 * GB,
        )
      )
      .isEqualTo(18)

    // Check case when there's not enough memory, but should still return 1
    assertThat(
        LintParallelBuildService.calculateMaxParallelUsages(
          projectOptions,
          runInProcess = false,
          maxRuntimeMemory = 1 * GB,
          totalPhysicalMemory = 1 * GB,
        )
      )
      .isEqualTo(1)

    // Check case when totalPhysicalMemory is null
    assertThat(
        LintParallelBuildService.calculateMaxParallelUsages(
          projectOptions,
          runInProcess = false,
          maxRuntimeMemory = 10 * GB,
          totalPhysicalMemory = null,
        )
      )
      .isNull()

    // Check case when user specifies invalid lint heap size
    whenever(projectOptions.get(LINT_HEAP_SIZE)).thenReturn("invalid")
    try {
      LintParallelBuildService.calculateMaxParallelUsages(
        projectOptions,
        runInProcess = false,
        maxRuntimeMemory = 1 * GB,
        totalPhysicalMemory = 1 * GB,
      )
      fail("expected RuntimeException")
    } catch (e: RuntimeException) {
      assertThat(e.message).isEqualTo("Failed to parse ${LINT_HEAP_SIZE.propertyName} \"invalid\".")
    }
  }

  @Test
  fun testHeterogeneousCalculation() {
    // Verify that given the same projectOptions instance, in-process and out-of-process compute distinct limits
    whenever(projectOptions.get(BooleanOption.RUN_LINT_IN_PROCESS)).thenReturn(true)
    whenever(projectOptions.get(LINT_RESERVED_MEMORY_PER_TASK)).thenReturn("1g")
    whenever(projectOptions.get(LINT_HEAP_SIZE)).thenReturn("2g")

    val inProcessLimit =
      LintParallelBuildService.calculateMaxParallelUsages(
        projectOptions,
        runInProcess = true,
        maxRuntimeMemory = 20 * GB,
        totalPhysicalMemory = 40 * GB,
      )
    val outOfProcessLimit =
      LintParallelBuildService.calculateMaxParallelUsages(
        projectOptions,
        runInProcess = false,
        maxRuntimeMemory = 20 * GB,
        totalPhysicalMemory = 40 * GB,
      )

    assertThat(inProcessLimit).isEqualTo(15)
    assertThat(outOfProcessLimit).isEqualTo(18)
    assertThat(inProcessLimit).isNotEqualTo(outOfProcessLimit)
  }

  @Test
  fun testServiceRegistrationAsDistinctInstances() {
    val project = ProjectBuilder.builder().build()
    whenever(projectOptions.get(BooleanOption.RUN_LINT_IN_PROCESS)).thenReturn(true)

    val inProcessService = project.gradle.sharedServices.getLintParallelBuildService(projectOptions, runInProcess = true)
    val outOfProcessService = project.gradle.sharedServices.getLintParallelBuildService(projectOptions, runInProcess = false)

    assertThat(inProcessService).isNotNull()
    assertThat(outOfProcessService).isNotNull()

    val inProcessReg =
      requireNotNull(
        project.gradle.sharedServices.registrations.findByName(LintParallelBuildService.LINT_PARALLEL_BUILD_SERVICE_IN_PROCESS)
      )
    val outOfProcessReg =
      requireNotNull(
        project.gradle.sharedServices.registrations.findByName(LintParallelBuildService.LINT_PARALLEL_BUILD_SERVICE_OUT_OF_PROCESS)
      )

    assertThat(inProcessReg).isNotEqualTo(outOfProcessReg)

    // Verify each registration has its maxParallelUsages set
    assertThat(inProcessReg.maxParallelUsages.orNull).isNotNull()
    assertThat(outOfProcessReg.maxParallelUsages.orNull).isNotNull()
  }

  @Test
  fun testGetLintParallelBuildServiceInAndOutOfProcess() {
    val project = ProjectBuilder.builder().build()
    whenever(projectOptions.get(BooleanOption.RUN_LINT_IN_PROCESS)).thenReturn(true)
    whenever(projectOptions.getProvider(BooleanOption.RUN_LINT_IN_PROCESS)).thenReturn(Providers.of(true))

    val inProcessProvider = project.gradle.sharedServices.getLintParallelBuildService(projectOptions, runInProcess = true)
    val outOfProcessProvider = project.gradle.sharedServices.getLintParallelBuildService(projectOptions, runInProcess = false)

    assertThat(inProcessProvider).isNotSameInstanceAs(outOfProcessProvider)
    assertThat(inProcessProvider.isPresent).isTrue()
    assertThat(outOfProcessProvider.isPresent).isTrue()
  }
}

private const val GB = 1024 * 1024 * 1024L
