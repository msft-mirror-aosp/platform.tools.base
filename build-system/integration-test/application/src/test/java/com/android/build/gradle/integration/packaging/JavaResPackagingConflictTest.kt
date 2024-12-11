/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.packaging

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.builder.merge.DuplicateRelativeFileException
import com.android.testutils.TestInputsGenerator
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

private val basicSetupAction: GradleBuildDefinition.() -> Unit = {
    androidApplication {
        dependencies {
            implementation(project(":library"))
            implementation(project(":library2"))
        }
    }
    androidLibrary(":library") { }
    androidLibrary(":library2") { }
}

class JavaResPackagingConflictTest {
    @get:Rule
    val rule = GradleRule.from(basicSetupAction)

    @Test
    fun testConflictBetweenLibraries() {
        val build = rule.build
        val library = build.androidLibrary(":library")
        val library2 = build.androidLibrary(":library2")

        library.files.add("src/main/resources/foo.txt", "lib_content")
        library2.files.add("src/main/resources/foo.txt", "lib2_content")

        val result = build.executor
            .expectFailure()
            .run(":app:mergeDebugJavaResource")

        val originCause = findCause(result.exception!!)
        Truth.assertThat(originCause).named("Cause as DuplicateRelativeFileException").isNotNull()
        Truth.assertThat(originCause?.message).isEqualTo("""
2 files found with path 'foo.txt' from inputs:
 - project(":library")
 - project(":library2")
Adding a packaging block may help, please refer to
https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/Packaging
for more information
            """.trimIndent())
    }
}

class JavaResPackagingConflictWithIncludedBuildTest {

    @get:Rule
    val rule = GradleRule.from {
        basicSetupAction()
        androidApplication {
            dependencies {
                implementation("included.build:anotherLib:1.0")
            }
        }
        includedBuild("includedBuild") {
            androidLibrary(":anotherLib") {
                group = "included.build"
                version = "1.0"
            }
        }
    }

    @Test
    fun testConflictBetweenLibraries() {
        val build = rule.build
        val library = build.androidLibrary(":library")
        val library2 = build.androidLibrary(":library2")
        val library3 = build.includedBuild("includedBuild").androidLibrary(":anotherLib")

        library.files.add("src/main/resources/foo.txt", "lib_content")
        library2.files.add("src/main/resources/foo.txt", "lib2_content")
        library3.files.add("src/main/resources/foo.txt", "lib3_content")

        val result = build.executor
            // PROJECT_ISOLATION mode is not supported with includedBuilds
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .expectFailure()
            .run(":app:mergeDebugJavaResource")

        val originCause = findCause(result.exception!!)
        Truth.assertThat(originCause).named("Cause as DuplicateRelativeFileException").isNotNull()
        Truth.assertThat(originCause?.message).isEqualTo("""
3 files found with path 'foo.txt' from inputs:
 - project(":library") - Build: :
 - project(":library2") - Build: :
 - project(":anotherLib") - Build: :includedBuild
Adding a packaging block may help, please refer to
https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/Packaging
for more information
            """.trimIndent())
    }
}

class JavaResPackagingConflictWithExternalLibrariesTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withMavenRepository {
            jar("com.example:jar:1.0")
                .setJar(TestInputsGenerator.jarWithTextEntries("foo.txt" to "blah"))
        }.from {
            basicSetupAction()
            androidApplication {
                dependencies {
                    implementation("com.example:jar:1.0")
                }
            }
        }

    @Test
    fun testConflictBetweenLibraries() {
        val build = rule.build
        val library = build.androidLibrary(":library")
        val library2 = build.androidLibrary(":library2")

        library.files.add("src/main/resources/foo.txt", "lib_content")
        library2.files.add("src/main/resources/foo.txt", "lib2_content")

        val result = build.executor
            .expectFailure()
            .run(":app:mergeDebugJavaResource")

        val originCause = findCause(result.exception!!)
        Truth.assertThat(originCause).named("Cause as DuplicateRelativeFileException").isNotNull()
        Truth.assertThat(originCause?.message).isEqualTo("""
3 files found with path 'foo.txt' from inputs:
 - project(":library")
 - project(":library2")
 - com.example:jar:1.0/jar-1.0.jar
Adding a packaging block may help, please refer to
https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/Packaging
for more information
            """.trimIndent())
    }
}

private fun findCause(e: Throwable): Throwable? {
    var cause: Throwable? = e
    while (cause?.cause != null) {
        cause = cause.cause
        if (cause?.javaClass?.canonicalName == DuplicateRelativeFileException::class.qualifiedName) {
            return cause
        }
    }

    return null
}
