/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.build.gradle.integration.gradleapi

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.integration.common.runner.FilterableParameterized
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.internal.errors.DeprecationReporter
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption
import com.android.builder.model.SyncIssue
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(FilterableParameterized::class)
class ObsoleteApiTest(private val provider: TestProjectProvider) {

    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}")
        fun setUps() = listOf(
            TestProjectProvider("Kotlin") {
                androidKotlinApplication { }
                gradleProperties {
                    add(BooleanOption.BUILT_IN_KOTLIN, false)
                }
            }, TestProjectProvider("Java") {
                androidJavaApplication {
                    pluginCallbacks += LegacyCallback::class.java
                }
                gradleProperties {
                    add(BooleanOption.BUILT_IN_KOTLIN, false)
                    add(BooleanOption.USE_NEW_DSL, false)
                }
            }
        )
    }

    class LegacyCallback: LegacyApplicationCallback {
        override fun handleExtension(
            project: Project,
            extension: BaseAppModuleExtension
        ) {
            extension.applicationVariants.all { variant ->
                println(variant.javaCompile.name)
            }
        }
    }

    @get:Rule
    val rule = GradleRule.configure()
        .disableBrokenBuiltInKotlinOptOutChecks()
        .from(configAction = provider.configAction)

    @Test
    fun `test via model`() {
        val build = rule.build
        val model = build.modelBuilder
            // legacy incremental transform uses deprecated gradle api
            .withFailOnWarning(false)
            .with(BooleanOption.DEBUG_OBSOLETE_API, true)
            .suppressOptionWarning(BooleanOption.BUILT_IN_KOTLIN)
            .suppressOptionWarning(BooleanOption.USE_NEW_DSL)
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels()
        val issueModel = model.container.singleProjectInfo.issues ?: throw RuntimeException("failed to get issue model")
        val syncIssues = issueModel.syncIssues

        when (provider.name) {
            "Kotlin" -> {
                Truth.assertThat(syncIssues).hasSize(0)
            }
            "Java" -> {
                Truth.assertThat(syncIssues).hasSize(1)
                val warningMsg = syncIssues.first().message
                Truth.assertThat(warningMsg).contains(
                    "API 'variant.getJavaCompile()' is obsolete and has been replaced with 'variant.getJavaCompileProvider()'.\n" +
                            "${DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT.getDeprecationTargetMessage()}\n" +
                            "For more information, see https://d.android.com/r/tools/task-configuration-avoidance.\n" +
                            "\n" +
                            "REASON: It is currently called from the following trace:")
            }
            else -> throw RuntimeException("Unsupported type")
        }
    }

    @Test
    fun `Test from command line`() {
        val build = rule.build
        val result = build.executor
            // legacy incremental transform uses deprecated gradle api
            .withFailOnWarning(false)
            .with(BooleanOption.DEBUG_OBSOLETE_API, true).run("help")

        result.stdout.use {
            when(provider.name) {
                "Kotlin" -> {
                    ScannerSubject.assertThat(it).doesNotContain("API 'variant.getJavaCompile()' is obsolete")
                }
                "Java" -> {
                    ScannerSubject.assertThat(it).contains(
                        "API 'variant.getJavaCompile()' is obsolete and has been replaced with 'variant.getJavaCompileProvider()'.\n" +
                                "${DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT.getDeprecationTargetMessage()}\n" +
                                "For more information, see https://d.android.com/r/tools/task-configuration-avoidance.\n" +
                                "\n" +
                                "REASON: It is currently called from the following trace:")
                }
                else -> throw RuntimeException("Unsupported type")
            }
        }
    }
}

class TestProjectProvider(
    val name: String,
    val configAction: GradleBuildDefinition.() -> Unit
) {
    override fun toString(): String {
        return name
    }
}
