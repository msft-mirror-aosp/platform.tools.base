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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.localRepositories
import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.utils.FileUtils
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class KotlinMultiplatformHostTestIncludesAndroidResourcesTest {

    companion object {
        const val SDK_VERSION: String = "9-robolectric-4913185-2-i4"
        val PLATFORM_JAR_NAME: String = String.format("android-all-instrumented-%s.jar", SDK_VERSION)
        val PLATFORM_JAR_RELATIVE_PATH: String = String.format(
            "org/robolectric/android-all-instrumented/%s/%s",
            SDK_VERSION, PLATFORM_JAR_NAME
        )
    }

    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .create()

    @Before
    fun setUp() {
        val platformJar: Path = localRepositories
            .firstOrNull { Files.exists(it.resolve(PLATFORM_JAR_RELATIVE_PATH)) }
            ?.resolve(PLATFORM_JAR_RELATIVE_PATH)
            ?: throw AssertionError(
                "Failed to find Robolectric platform jar $PLATFORM_JAR_RELATIVE_PATH in prebuilts."
            )


        val robolectricLibs = project.file("robolectric-libs").toPath()
        Files.createDirectories(robolectricLibs)

        FileUtils.copyFile(
            platformJar,
            robolectricLibs.resolve(PLATFORM_JAR_NAME)
        )

        FileUtils.writeToFile(
            project.getSubproject("kmpHostTestOnlyLib")
                .file("src/androidHostTest/kotlin/com/example/shared/ExampleUnitTest.kt"),
            """
                package com.example.kmpHostTestOnlyLib;

                import org.junit.runner.RunWith
                import org.robolectric.RobolectricTestRunner
                import kotlin.test.Test
                import kotlin.test.assertEquals

                @RunWith(RobolectricTestRunner::class)
                class ExampleUnitTest {
                    @Test
                    fun addition_isCorrect() {
                        assertEquals(4, 2 + 2)
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testAndroidHostTestRuns() {
        project.executor().run(":kmpHostTestOnlyLib:testAndroidHostTest")
    }

    @Test
    fun testAndroidHostTestRunsNotAffectedByDeviceTestsBeingEnabled() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpHostTestOnlyLib").ktsBuildFile,
            """
                kotlin.androidLibrary {
                    withDeviceTestBuilder {}.configure {
                        targetSdk { version = release(libs.versions.latestCompileSdk.get().toInt()) }
                    }
                }
            """.trimIndent())
        project.executor().run(":kmpHostTestOnlyLib:testAndroidHostTest")
    }

    @Test
    fun testAndroidHostTestRunsWithAndroidResourcesEnabled() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpHostTestOnlyLib").ktsBuildFile,
            """
                kotlin.androidLibrary  {
                    androidResources.enable = true
                }
            """.trimIndent())
        project.executor().run(":kmpHostTestOnlyLib:testAndroidHostTest")
    }
}
