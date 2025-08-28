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

package com.android.build.gradle.integration.dependencies

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.AndroidGradlePluginProjectFlags.BooleanFlag.ENABLE_COMPILE_RUNTIME_CLASSPATH_ALIGNMENT
import com.android.builder.model.v2.ide.SyncIssue
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CompileAndRuntimeClasspathTest(private val enableAlignment: Boolean) {

    companion object {

        @Parameterized.Parameters(name = "enableAlignment_{0}")
        @JvmStatic
        fun parameters() = listOf(true, false)
    }

    @JvmField
    @Rule
    val project: GradleTestProject = GradleTestProject.builder()
        .fromTestApp(HelloWorldApp.forPlugin("com.android.application"))
        .addGradleProperties("${BooleanOption.USE_DEPENDENCY_CONSTRAINTS.propertyName}=$enableAlignment")
        .create()

    @Test
    fun `Higher Compile than Runtime causes failure`() {
        project.buildFile.appendText(
            """
            |dependencies {
            |    compileOnly'com.google.guava:guava:20.0'
            |    runtimeOnly'com.google.guava:guava:19.0'
            |}""".trimMargin()
        )

        if (enableAlignment) {
            val result = project.executor().expectFailure().run("assembleDebug")
            result.assertErrorContains(
                "> Could not resolve all files for configuration ':debugCompileClasspath'.\n" +
                        "   > Could not resolve com.google.guava:guava:20.0.\n" +
                        "     Required by:\n" +
                        "         root project 'project'\n" +
                        "      > Cannot find a version of 'com.google.guava:guava' that satisfies the version constraints:\n" +
                        "           Dependency path ':project:unspecified' --> 'com.google.guava:guava:20.0'\n" +
                        "           Constraint path ':project:unspecified' --> 'com.google.guava:guava:{strictly 19.0}' because of the following reason:" +
                        " version resolved in configuration ':debugRuntimeClasspath' by consistent resolution\n"
            )
        } else {
            val result = project.executor().run("dependencies")
            result.assertOutputContains(
                """
                debugCompileClasspath - Resolved configuration for compilation for variant: debug
                \--- com.google.guava:guava:20.0
                """.trimIndent()
            )
        }
    }

    @Test
    fun `Lower Compile than Runtime leads to promoted version`() {
        project.buildFile.appendText(
            """
            |dependencies {
            |    compileOnly'com.google.guava:guava:19.0'
            |    runtimeOnly'com.google.guava:guava:20.0'
            |}""".trimMargin()
        )

        val result = project.executor().run("dependencies")
        if (enableAlignment) {
            result.assertOutputContains(
                """
                debugCompileClasspath - Resolved configuration for compilation for variant: debug
                +--- com.google.guava:guava:19.0 -> 20.0
                \--- com.google.guava:guava:{strictly 20.0}
                """.trimIndent()
            )
        } else {
            result.assertOutputContains(
                """
                debugCompileClasspath - Resolved configuration for compilation for variant: debug
                \--- com.google.guava:guava:19.0
                """.trimIndent()
            )
        }
    }

    @Test
    fun `value is represented in the model`() {
        val models = project.modelV2()
            // ignore performance warning
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels()
        val flags = models.container.getProject(":").androidProject!!.flags
        assertThat(ENABLE_COMPILE_RUNTIME_CLASSPATH_ALIGNMENT.getValue(flags))
            .named("tooling model ENABLE_COMPILE_RUNTIME_CLASSPATH_ALIGNMENT")
            .isEqualTo(enableAlignment)
    }

}
