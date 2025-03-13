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
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.SyncIssue
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

class LibraryConstraintsSyncIssueTest {
    @get:Rule
    val rule = GradleRule.from {
            androidApplication(":app") {}
            androidLibrary(":lib") {}
        }

    @Test
    fun testWithWarningEnabledAndConstraintsNotExcluded() {
        val models = rule.build {
            gradleProperties {
                add(BooleanOption.GENERATE_SYNC_ISSUE_WHEN_LIBRARY_CONSTRAINTS_ARE_ENABLED, true)
                add(BooleanOption.EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS, false)
            }
        }.modelBuilder.ignoreSyncIssues().fetchModels().container

        models.getProject(":app").assertIssueExists()
        models.getProject(":lib").assertIssueExists()
    }

    @Test
    fun testWithWarningEnabledAndConstraintsExcluded() {
        val models = rule.build {
            gradleProperties {
                add(BooleanOption.GENERATE_SYNC_ISSUE_WHEN_LIBRARY_CONSTRAINTS_ARE_ENABLED, true)
                add(BooleanOption.EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS, true)
            }
        }.modelBuilder.ignoreSyncIssues().fetchModels().container

        models.getProject(":app").assertIssueDoesNotExist()
        models.getProject(":lib").assertIssueDoesNotExist()
    }

    @Test
    fun testWithWarningDisabledAndConstraintsNotExcluded() {
        val models = rule.build {
            gradleProperties {
                add(BooleanOption.GENERATE_SYNC_ISSUE_WHEN_LIBRARY_CONSTRAINTS_ARE_ENABLED, false)
                add(BooleanOption.EXCLUDE_LIBRARY_COMPONENTS_FROM_CONSTRAINTS, false)
            }
        }.modelBuilder.ignoreSyncIssues().fetchModels().container

        models.getProject(":app").assertIssueDoesNotExist()
        models.getProject(":lib").assertIssueDoesNotExist()
    }
}

private fun ModelContainerV2.ModelInfo.assertIssueExists() {
    val issues = issues?.syncIssues
    Truth.assertThat(issues).isNotNull()
    Truth.assertThat(issues!!).hasSize(1)
    Truth.assertThat(issues.single().severity).isEqualTo(SyncIssue.SEVERITY_WARNING)
    Truth.assertThat(issues.single().type).isEqualTo(SyncIssue.TYPE_LIBRARY_CONSTRAINTS_SHOULD_BE_DISABLED)
}

private fun ModelContainerV2.ModelInfo.assertIssueDoesNotExist() {
    val issues = issues?.syncIssues
    Truth.assertThat(issues).isNotNull()
    Truth.assertThat(issues!!).hasSize(0)
}
