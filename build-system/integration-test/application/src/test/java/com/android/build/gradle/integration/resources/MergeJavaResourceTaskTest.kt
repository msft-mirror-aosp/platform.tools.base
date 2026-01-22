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

package com.android.build.gradle.integration.resources

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.testutils.TestInputsGenerator.jarWithTextEntries
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.exists

/**
 * Tests related to [MergeJavaResourceTask]
 */
class MergeJavaResourceTaskTest {

    @get:Rule
    val rule = GradleRule.configure().withMavenRepository {
        jar(
            "com.example:lib1:0.1"
        ).addTextFile("conflict_res", "a")
        jar(
            "com.example:lib2:0.1"
        ).addTextFile("conflict_res", "b")
        jar(
            "com.example:libWithAdditionalArtifact:0.1"
        ).addTextFile("content1", "a")
            .addTextFile("content2", "b")
    }.from {
        androidApplication {
            android {
                namespace = "com.example.test"
                buildFeatures {
                    buildConfig = true
                }
            }
        }
    }

    @Test
    fun ensureHelpfulErrorMessageOnConflict() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    implementation("com.example:lib1:0.1")
                    implementation("com.example:lib2:0.1")
                }
            }
        }
        val failure = build.executor.expectFailure().run("assembleDebug")
        // Ensure that the inputs are included in the stderr output.
        failure.assertFailureMessage().contains("2 files found with path 'conflict_res' from inputs:\n - ")
    }

    @Test
    fun ensureNoJavacDependencyIfNoAnnotationProcessor() {
        val build = rule.build.executor.run("clean", "app:mergeDebugJavaResource")
        assertThat(build.didWorkTasks).doesNotContain("app:compileDebugJavaWithJavac")
    }

    @Test
    fun ensureJavacDependencyIfAnnotationProcessor() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    add("annotationProcessor", localJar("empty.jar") { jarWithTextEntries()})
                }
            }
        }
        build.executor.run("clean", "app:mergeDebugJavaResource").also {
            if (build.androidApplication().resolve(
                    InternalArtifactType.COMPILE_BUILD_CONFIG_JAR).exists()) {
                assertThat(it.didWorkTasks).doesNotContain(":app:compileDebugJavaWithJavac")
            } else {
                assertThat(it.didWorkTasks).contains(":app:compileDebugJavaWithJavac")
            }
        }
    }

    @Test
    fun ensureJavacDependencyIfAnnotationProcessorAddedViaDefaultDependencies() {
        val build = rule.build
        build.androidApplication().files.update("build.gradle").append(
            """configurations['annotationProcessor'].defaultDependencies { dependencies ->
                |    dependencies.add(owner.project.dependencies.create(files('empty.jar')))
                |}""".trimMargin()
        )
        val gradleBuildResult = build.executor.run("clean", ":app:mergeDebugJavaResource")
        if (build.androidApplication().resolve(
                InternalArtifactType.COMPILE_BUILD_CONFIG_JAR.getFolderName()).exists()) {
            assertThat(gradleBuildResult.didWorkTasks).doesNotContain("app:compileDebugJavaWithJavac")
        } else {
            assertThat(gradleBuildResult.didWorkTasks).contains(":app:compileDebugJavaWithJavac")
        }
    }

    @Test
    fun ensureJavaResIsNotRunningWhenOnlyClassesChange() {
        val build = rule.build {
            androidApplication {
                files {
                    add(
                        "src/main/com/android/tests/basic/NewSourceFile.java",
                        """
            package com.android.tests.basic;

            class NewSourceFile {
                public static int foo() {
                    return 154;
                }
            }
        """.trimIndent()
                    )
                }
            }
        }
        build.executor.run("assembleDebug")

        build.androidApplication().files.update("src/main/com/android/tests/basic/NewSourceFile.java").replaceWith(
            """
            package com.android.tests.basic;

            class NewSourceFile {
                public static int foo() {
                    return 154;
                }
            }
        """.trimIndent()
        )
        val gradleBuildResult = build.executor.run("assembleDebug")
        assertThat(gradleBuildResult.upToDateTasks).contains(":app:mergeDebugJavaResource")
    }

    @Test
    fun ensureJavaResIsNotRunningWhenOnlyNativeLibsChange() {
        val build = rule.build
        build.executor.run("assembleDebug")
        val newNativeLib = build.androidApplication().resolve("src/main/jniLibs/x86/library.so")
        assertThat(newNativeLib.exists()).isFalse()
        FileUtils.writeToFile(newNativeLib.toFile(), "some_native_lib")
        assertThat(newNativeLib.exists()).isTrue()
        val gradleBuildResult = build.executor.run("assembleDebug")
        assertThat(gradleBuildResult.upToDateTasks).contains(":app:mergeDebugJavaResource")
    }

    @Test
    fun ensureJavaResIsRunningWhenResourcesIsAdded() {
        val build = rule.build
        build.executor.run("assembleDebug")
        val newSourceFile = build.androidApplication().resolve("src/main/resources/com/android/tests/app.txt")
        assertThat(newSourceFile.exists()).isFalse()
        FileUtils.writeToFile(newSourceFile.toFile(), """does_not_matter""".trimIndent())
        val gradleBuildResult = build.executor.run("assembleDebug")
        val javaResTask = gradleBuildResult.findTask("app:mergeDebugJavaResource")
        assertThat(gradleBuildResult.upToDateTasks).doesNotContain(":app:mergeDebugJavaResource")
    }

    @Test
    fun ensureJavaResIsRunningWhenResourcesIsChanged() {
        val build = rule.build
        build.executor.run("assembleDebug")
        val newSourceFile = build.androidApplication().resolve("src/main/resources/com/android/tests/app.txt")
        assertThat(newSourceFile.exists()).isFalse()
        FileUtils.writeToFile(newSourceFile.toFile(), """does_not_matter""".trimIndent())
        build.executor.run("assembleDebug")
        FileUtils.writeToFile(newSourceFile.toFile(), """does_not_matter_version_2""".trimIndent())
        val gradleBuildResult = build.executor.run("assembleDebug")
        val javaResTask = gradleBuildResult.findTask("app:mergeDebugJavaResource")
        assertThat(gradleBuildResult.upToDateTasks).doesNotContain(":app:mergeDebugJavaResource")
    }

    @Test
    fun ensureJavaResIsRunningWhenResourcesIsRemoved() {
        val build = rule.build
        build.executor.run("assembleDebug")
        val newSourceFile = build.androidApplication().resolve("src/main/resources/com/android/tests/app/txt")
        assertThat(newSourceFile.exists()).isFalse()
        FileUtils.writeToFile(newSourceFile.toFile(), """does_not_matter""".trimIndent())
        build.executor.run("assembleDebug")
        assertThat(newSourceFile.toFile().delete()).isTrue()
        val gradleBuildResult = build.executor.run("assembleDebug")
        val javaResTask = gradleBuildResult.findTask("app:mergeDebugJavaResource")
        assertThat(gradleBuildResult.upToDateTasks).doesNotContain(":app:mergeDebugJavaResource")
    }

    @Test
    fun ensureJavaResIsRunningWhenDirIsAdded() {
        val build = rule.build
        build.executor.run(":app:mergeDebugJavaResource")
        val newResourcesDir = build.androidApplication().resolve("src/main/resources/com/android/tests/empty_dir")
        assertThat(newResourcesDir.exists()).isFalse()
        assertThat(newResourcesDir.toFile().mkdirs()).isTrue()
        build.executor.run(":app:mergeDebugJavaResource").run {
            assertTask(":app:processDebugJavaRes").didWork()
            assertTask(":app:mergeDebugJavaResource").didWork()
        }
    }

    // Regression test for b/377366954
    @Test
    fun javaResIncrementalBuildWithAdditionalArtifact() {
        val build = rule.build {
            androidApplication {
                android {
                    dependencies {
                        implementation("com.example:libWithAdditionalArtifact:0.1")
                    }
                }
            }
        }
        build.executor.run("clean", "app:mergeDebugJavaResource")
        val newResourceFile = build.androidApplication().resolve("src/main/resources/file.txt")
        assertThat(newResourceFile.exists()).isFalse()
        FileUtils.writeToFile(newResourceFile.toFile(), "resource")
        build.executor.run(":app:mergeDebugJavaResource")
    }
}
