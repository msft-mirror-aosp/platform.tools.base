/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.builder
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification.ModifiedProjectTest
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.utils.FileUtils
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test


class MessageRewrite2Test {
    companion object {
        @ClassRule
        @JvmField
        var project: GradleTestProject = builder().fromTestProject("flavored").create()

        @JvmStatic
        @BeforeClass
        @Throws(Exception::class)
        fun setUp() {
            project.execute("assembleDebug")
        }
    }

    @Test
    fun testErrorInStrings() {
        TemporaryProjectModification.doTest(project) { it: TemporaryProjectModification? ->
                it!!.replaceInFile(
                    "src/main/res/values/strings.xml", "default text", "don't <> work"
                )
                val result: GradleBuildResult =
                    project.executor().expectFailure().run("assembleDebug")
                result.stdout.use { scanner ->
                    assertThat(scanner)
                        .contains(
                            FileUtils.join(
                                "src", "main", "res", "values", "strings.xml"
                            )
                        )
                }
            }

        project.execute("assembleDebug")
    }

    @Test
    fun testErrorInStringsForCompile() {
        // Incorrect strings.xml should cause AAPT to throw an error and we should rewrite it to
        // point to the original file.
        TemporaryProjectModification.doTest(project) { it: TemporaryProjectModification? ->
                it!!.replaceInFile("src/main/res/values/strings.xml", "default text", "<%s %d>")
                val result: GradleBuildResult =
                    project.executor().expectFailure().run("assembleDebug")
                result.stdout.use { stdout ->
                    assertThat(stdout)
                        .contains(
                            FileUtils.join(
                                "src", "main", "res", "values", "strings.xml"
                            )
                        )
                }
            }

        // AAPT1 and AAPT2 (with the legacy flag) should allow multiple substitutions specified in a
        // non=positional format - an error should not be thrown.
        TemporaryProjectModification.doTest(
            project,
            ModifiedProjectTest { it: TemporaryProjectModification? ->
                it!!.replaceInFile("src/main/res/values/strings.xml", "default text", "%s %d")
                project.executor().run("assembleDebug")
            })

        project.execute("assembleDebug")
    }
}
