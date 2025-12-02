/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.integration.annotationprocessor

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.runner.FilterableParameterized
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.getOutputDir
import com.android.build.gradle.tasks.ANNOTATION_PROCESSOR_LIST_FILE_NAME
import com.android.testutils.TestInputsGenerator.writeJarWithEmptyEntries
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Integration test to ensure that plugin binary compatibility of AGP APIs is preserved. */
@RunWith(FilterableParameterized::class)
class KspJavaPreCompileTest(private val useKagp: Boolean) {

    companion object {

        @Parameterized.Parameters(name = "useKagp_{0}")
        @JvmStatic
        fun parameters() = listOf(true, false)
    }

    @get:Rule
    val project = GradleTestProject.builder().fromTestProject("kotlinAppWithKsp")
        .create()

    @Before
    fun setup() {
        if (useKagp) {
            TestFileUtils.appendToFile(
                project.gradlePropertiesFile, """
                android.builtInKotlin=false
                android.newDsl=false
            """.trimIndent()
            )

            TestFileUtils.searchAndReplace(
                project.buildFile, "dependencies {", """
                dependencies {
                    classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:\$\{libs.versions.kotlinVersion.get()}"
            """.trimIndent()
            )

            TestFileUtils.searchAndReplace(
                project.getSubproject("app").buildFile,
                "apply plugin: 'com.android.application'", """
                apply plugin: 'com.android.application'
                apply plugin: 'kotlin-android'
            """.trimIndent()
            )

            TestFileUtils.searchAndReplace(
                project.getSubproject("app").buildFile,
                "buildToolsVersion = libs.versions.buildToolsVersion.get()",
                """
                    buildToolsVersion = libs.versions.buildToolsVersion.get()

                    kotlinOptions {
                        jvmTarget = JavaVersion.VERSION_11
                    }
            """.trimIndent()
            )

            TestFileUtils.searchAndReplace(
                project.getSubproject("app").buildFile, "dependencies {", """
                dependencies {
                    implementation "org.jetbrains.kotlin:kotlin-stdlib:\$\{libs.versions.kotlinVersion.get()}"
            """.trimIndent()
            )
        }
    }

    @Test
    fun kspJavaPreCompileTest() {
        project.executor().run(":app:javaPreCompileDebug")

        val annotationProcessorList =
            InternalArtifactType.ANNOTATION_PROCESSOR_LIST.getOutputDir(project.getSubproject("app").buildDir)
                .resolve("debug/javaPreCompileDebug/$ANNOTATION_PROCESSOR_LIST_FILE_NAME").readText()
        Truth.assertThat(annotationProcessorList).isEqualTo(
            "{\"mock-processor.jar (project :mock-processor)\":\"KSP_PROCESSOR\"}")
    }

    /** Regression test for b/331806519. */
    @Test
    fun annotationProcessorsFromKspClasspathShouldBeIgnored() {
        val processorJar =
            project.getSubproject(":app").projectDir.resolve("annotationProcessor.jar")
        writeJarWithEmptyEntries(
            processorJar.toPath(),
            listOf("META-INF/services/javax.annotation.processing.Processor")
        )
        project.getSubproject(":app").buildFile.appendText("""

            dependencies {
              ksp files("${processorJar.invariantSeparatorsPath}")
            }
        """.trimIndent())

        project.executor().run(":app:javaPreCompileDebug")

        val annotationProcessorList =
            InternalArtifactType.ANNOTATION_PROCESSOR_LIST.getOutputDir(project.getSubproject("app").buildDir)
                .resolve("debug/javaPreCompileDebug/$ANNOTATION_PROCESSOR_LIST_FILE_NAME").readText()
        Truth.assertThat(annotationProcessorList).isEqualTo(
            "{\"mock-processor.jar (project :mock-processor)\":\"KSP_PROCESSOR\"}")
    }

}
