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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.utils.FileUtils
import org.junit.Rule
import org.junit.Test

class MessageRewrite2Test {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                namespace = "com.example.api.use"
                defaultConfig.applicationId = "com.example.api.use"
            }
            files {
                add(
                    "src/main/res/values/strings.xml",
                    //language=xml
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="app_name">###</string>
                            <string name="text">default text</string>
                        </resources>
                    """.trimIndent()
                )
            }
        }
    }


    @Test
    fun testProjectIsOk() {
        rule.build.executor.run("assembleDebug")
    }

    @Test
    fun testErrorInStrings() {
        val build = rule.build {
            androidApplication {
                files.update("src/main/res/values/strings.xml")
                .searchAndReplace("default text", "don't <> work", lenient = true)
            }
        }
        build.executor.expectFailure().run("assembleDebug").apply {
            assertErrorContains(
                FileUtils.join(
                    "src", "main", "res", "values", "strings.xml"
                )
            )
        }
    }

    @Test
    fun testErrorInStringsForCompile() {
        // Incorrect strings.xml should cause AAPT to throw an error and we should rewrite it to
        // point to the original file.
        val build = rule.build {
            androidApplication {
                files.update("src/main/res/values/strings.xml")
                    .searchAndReplace("default text", "<%s %d>", lenient = true)
            }
        }
        build.executor.expectFailure().run("assembleDebug").apply {
            assertErrorContains(
                FileUtils.join(
                    "src", "main", "res", "values", "strings.xml"
                )
            )
        }
    }

    @Test
    fun testAllowMultipleSubstitution() {
        // AAPT1 and AAPT2 (with the legacy flag) should allow multiple substitutions specified in a
        // non=positional format - an error should not be thrown.
        val build = rule.build {
            androidApplication {
                files.update("src/main/res/values/strings.xml")
                    .searchAndReplace("default text", "%s %d", lenient = true)
            }
        }
        build.executor.run("assembleDebug")
    }

}
