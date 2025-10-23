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

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.TestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.common.fixture.app.KotlinHelloWorldApp
import com.android.build.gradle.integration.common.runner.FilterableParameterized
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.utils.disableBuiltInKotlin
import com.android.build.gradle.internal.errors.DeprecationReporter
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.SyncIssue
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(FilterableParameterized::class)
class ObsoleteApiTest(private val provider: TestProjectProvider) {

    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}")
        fun setUps() = listOf(
            TestProjectProvider("Kotlin") {
                KotlinHelloWorldApp.forPlugin("com.android.application")
            }, TestProjectProvider("Java") {
                HelloWorldApp.forPlugin("com.android.application")
                    .appendToBuild("""
                        android.applicationVariants.all { variant ->
                            println variant.getJavaCompile().getName()
                        }
                        """.trimIndent()
                )
            }
        )
    }

    @JvmField @Rule
    var project : GradleTestProject =
        GradleTestProject.builder()
            .fromTestApp(provider.provider.invoke())
            .disableBuiltInKotlin()
            .create()

    @Test
    fun `test via model`() {
        val model = project.modelV2()
            // legacy incremental transform uses deprecated gradle api
            .withFailOnWarning(false)
            .with(BooleanOption.DEBUG_OBSOLETE_API, true)
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels()
        val issueModel = model.container.singleProjectInfo.issues ?: throw RuntimeException("failed to get issue model")
        val syncIssues = issueModel.syncIssues

        when(provider.name) {
            "Kotlin" -> {
                Truth.assertThat(syncIssues).hasSize(0)
            }
            "Java" -> {
                Truth.assertThat(syncIssues).hasSize(1)
                val warningMsg = syncIssues.first().message
                Truth.assertThat(warningMsg).isEqualTo(
                    "API 'variant.getJavaCompile()' is obsolete and has been replaced with 'variant.getJavaCompileProvider()'.\n" +
                            "${DeprecationReporter.DeprecationTarget.TASK_ACCESS_VIA_VARIANT.getDeprecationTargetMessage()}\n" +
                            "For more information, see https://d.android.com/r/tools/task-configuration-avoidance.\n" +
                            "\n" +
                            "REASON: Called from: ${project.projectDir}${File.separatorChar}build.gradle:34\n" +
                            "WARNING: Debugging obsolete API calls can take time during configuration. It's recommended to not keep it on at all times.")
            }
            else -> throw RuntimeException("Unsupported type")
        }
    }

    @Test
    fun `Test from command line`() {
        val result = project.executor()
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
                                "REASON: Called from: ${project.projectDir}${File.separatorChar}build.gradle:34\n" +
                                "WARNING: Debugging obsolete API calls can take time during configuration. It's recommended to not keep it on at all times.")
                }
                else -> throw RuntimeException("Unsupported type")
            }
        }
    }

    @Test
    fun `test disabledApi from command line`() {
        val result = project.executor()
            .with(BooleanOption.ENABLE_LEGACY_VARIANT_API, false)
            .expectFailure()
            .run("help")

        when (provider.name) {
            "Java" -> result.assertErrorContains(
                """
                API 'applicationVariants' is obsolete.
                It will be removed in version 10.0 of the Android Gradle plugin.
                The legacy variant API is disabled by default in AGP 9.0, but can be re-enabled by adding
                    android.enableLegacyVariantApi=true
                to this project's gradle.properties file.
                For more information, see https://developer.android.com/studio/releases/gradle-plugin-api-updates.
                """.trimIndent()
            )

            "Kotlin" -> result.assertErrorContains(
                """
                API 'applicationVariants' is obsolete.
                It will be removed in version 10.0 of the Android Gradle plugin.
                The legacy variant API is disabled by default in AGP 9.0, but can be re-enabled by adding
                    android.enableLegacyVariantApi=true
                to this project's gradle.properties file.
                For more information, see https://developer.android.com/studio/releases/gradle-plugin-api-updates.

                REASON: The 'kotlin-android' plugin is currently calling this deprecated API.
                Please migrate this project to built-in kotlin (https://developer.android.com/r/tools/built-in-kotlin).
                """.trimIndent()
            )
        }
    }
}


class TestProjectProvider(
    val name: String,
    val provider: () -> TestProject
) {
    override fun toString(): String {
        return name
    }
}
