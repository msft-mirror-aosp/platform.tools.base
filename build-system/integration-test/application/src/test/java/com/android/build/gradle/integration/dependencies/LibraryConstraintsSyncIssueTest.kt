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

package com.android.build.gradle.integration.dependencies

import com.android.build.gradle.integration.common.fixture.ModelContainerV2
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.internal.ide.v2.SyncIssueImpl
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.SyncIssue
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

class LibraryConstraintsSyncIssueTest {
  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication(":app") {}
      androidLibrary(":lib") {}
    }

  @Test
  fun testWithWarningEnabledAndAllConstraintsApplied() {
    val models =
      rule
        .build {
          gradleProperties {
            add(BooleanOption.GENERATE_SYNC_ISSUE_WHEN_LIBRARY_CONSTRAINTS_ARE_ENABLED, true)
            add(BooleanOption.EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS, false)
            add(BooleanOption.USE_DEPENDENCY_CONSTRAINTS, true)
          }
        }
        .modelBuilder
        .ignoreSyncIssues()
        .fetchModels()
        .container

    models.getProject(":app").assertIssues(PERFORMANCE_WARNING)
    models.getProject(":lib").assertIssues(PERFORMANCE_WARNING)
  }

  @Test
  fun testWithWarningEnabledAndNoConstraintsApplied() {
    val models =
      rule
        .build {
          gradleProperties {
            add(BooleanOption.GENERATE_SYNC_ISSUE_WHEN_LIBRARY_CONSTRAINTS_ARE_ENABLED, true)
            // EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS doesn't matter in this case
            add(BooleanOption.USE_DEPENDENCY_CONSTRAINTS, false)
          }
        }
        .modelBuilder
        .ignoreSyncIssues()
        .fetchModels()
        .container

    models.getProject(":app").assertIssueDoesNotExist()
    models.getProject(":lib").assertIssueDoesNotExist()
  }

  @Test
  fun testWithWarningEnabledAndAllConstraintsAppliedWithLibrariesExcluded() {
    val models =
      rule
        .build {
          gradleProperties {
            add(BooleanOption.GENERATE_SYNC_ISSUE_WHEN_LIBRARY_CONSTRAINTS_ARE_ENABLED, true)
            add(BooleanOption.EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS, true)
            add(BooleanOption.USE_DEPENDENCY_CONSTRAINTS, true)
          }
        }
        .modelBuilder
        .ignoreSyncIssues()
        .fetchModels()
        .container

    models.getProject(":app").assertIssues(EXPERIMENTAL_USAGE_WARNING)
    // Experimental usage warnings are only issued once
    models.getProject(":lib").assertIssueDoesNotExist()
  }

  @Test
  fun testWithWarningDisabledAndAllConstraintsApplied() {
    val models =
      rule
        .build {
          gradleProperties {
            add(BooleanOption.GENERATE_SYNC_ISSUE_WHEN_LIBRARY_CONSTRAINTS_ARE_ENABLED, false)
            add(BooleanOption.EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS, false)
            add(BooleanOption.USE_DEPENDENCY_CONSTRAINTS, true)
          }
        }
        .modelBuilder
        .ignoreSyncIssues()
        .fetchModels()
        .container

    models.getProject(":app").assertIssueDoesNotExist()
    // Experimental usage warning only gets issued once
    models.getProject(":lib").assertIssueDoesNotExist()
  }
}

private fun ModelContainerV2.ModelInfo.assertIssues(vararg expected: SyncIssueImpl) {
  val issues = issues?.syncIssues
  Truth.assertThat(issues).isNotNull()
  // Comparing string representations.
  Truth.assertThat(issues!!.map { it.toString() }).containsExactlyElementsIn(expected.map { it.toString() })
}

private val PERFORMANCE_WARNING =
  SyncIssueImpl(
    severity = SyncIssue.SEVERITY_WARNING,
    type = SyncIssue.TYPE_LIBRARY_CONSTRAINTS_SHOULD_BE_DISABLED,
    data = null,
    message =
      """
      The property android.dependency.excludeLibraryComponentsFromConstraints improves project import performance for very large projects. It should be enabled to improve performance.
      To suppress this warning, add android.generateSyncIssueWhenLibraryConstraintsAreEnabled=false to gradle.properties
      """
        .trimIndent(),
    multiLineMessage = null,
  )

private val EXPERIMENTAL_USAGE_WARNING =
  SyncIssueImpl(
    severity = SyncIssue.SEVERITY_WARNING,
    type = SyncIssue.TYPE_UNSUPPORTED_PROJECT_OPTION_USE,
    data = BooleanOption.EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS.propertyName,
    message =
      """
    The option setting '${BooleanOption.EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS.propertyName}=true' is deprecated.
    The current default is 'false'.
    It will be removed in version 10.0 of the Android Gradle plugin.
    Following can be set instead to achieve a similar behaviour.
        android.dependency.useConstraints=false
    """
        .trimIndent(),
    multiLineMessage = null,
  )

private fun ModelContainerV2.ModelInfo.assertIssueDoesNotExist() {
  val issues = issues?.syncIssues
  Truth.assertThat(issues).isNotNull()
  Truth.assertThat(issues!!).isEmpty()
}
