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

package com.android.build.gradle.integration.api

import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.internal.publishing.AndroidArtifacts.PATH_SHARED_LIBRARY_RESOURCES_APK
import com.android.build.gradle.options.BooleanOption
import com.android.builder.internal.aapt.v2.Aapt2Exception
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.generateAarWithContent
import com.google.common.truth.Truth
import junit.framework.TestCase.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.readBytes
import kotlin.reflect.KClass

class SharedLibraryTest {

    @get:Rule
    val sharedTokenRule = GradleRule.from(folderName = "shared_token_project") {
        androidApplication {
            android {
                compileSdk = 34
                defaultConfig.applicationId = "com.android_token_test_lib"
            }
            files {
                add(
                    "src/main/res/values/strings.xml",
                    //language=xml
                    """
                        <resources>
                            <string name="oem_token_demo">TOKEN_DEMO</string>
                        </resources>""".trimIndent())
                add(
                    "src/main/res/values/values.xml",
                    //language=xml
                    "<resources />"
                )
            }
        }
    }

    private fun getSharedLibAsAar(): MavenRepoGenerator.Library = MavenRepoGenerator.Library(
        mavenCoordinate = "test:name:0.1",
        packaging = "aar",
        artifact = generateAarWithContent(
            packageName = "com.android.tokens_test_lib",
            extraFiles = mapOf(
                PATH_SHARED_LIBRARY_RESOURCES_APK to sharedTokenRule.build.androidApplication().withApk(ApkSelector.DEBUG) { file.readBytes() }
            )
        )
    )

    @get:Rule
    val consumerRule = GradleRule.from(folderName = "consumer_project_1") {
        androidApplication {
            android {
                defaultConfig.applicationId = "com.android.token_test"
            }
            files.add(
                "src/main/res/values/strings.xml",
                //language=xml
                """
                    <resources>
                        <string name="app_name">Name</string>
                        <string name="oem_token_demo_test">@*com.android_token_test_lib:string/oem_token_demo</string>
                    </resources>
                """.trimIndent())
        }
    }

    @Before
    fun setup() {
        sharedTokenRule.build.executor.run("assembleDebug")
    }

    @Test
    fun `token string resource reference is resolved`() {
        val build = consumerRule.build {
            androidApplication {
                addSharedDependency()
            }
            gradleProperties {
                add(BooleanOption.SUPPORT_OEM_TOKEN_LIBRARIES, true)
            }
        }

        val result = build.executor.run("assembleDebug")
        assertNull(result.exception)
    }

    @Test
    fun `token resolution fails when shared library support not enabled`() {
        val build = consumerRule.build {
            androidApplication {
                addSharedDependency()
            }
        }
        val result = build.executor.expectFailure().run("assembleDebug")
        result.assertExceptionCause(
            Aapt2Exception::class,
            """
                Android resource linking failed
                pkg.name.app-mergeDebugResources-2:/values/values.xml:4: error: resource com.android_token_test_lib:string/oem_token_demo not found.
                error: failed linking references.
            """.trimIndent()
        )
    }

    @Test
    fun `token resolution fails when dependency not included`() {
        val build = consumerRule.build {
            gradleProperties {
                add(BooleanOption.SUPPORT_OEM_TOKEN_LIBRARIES, true)
            }
        }
        val result = build.executor.expectFailure().run("assembleDebug")
        result.assertExceptionCause(
            Aapt2Exception::class,
            """
                Android resource linking failed
                pkg.name.app-mergeDebugResources-2:/values/values.xml:4: error: resource com.android_token_test_lib:string/oem_token_demo not found.
                error: failed linking references.
            """.trimIndent()
        )

        build.androidApplication().reconfigure { addSharedDependency() }
        val resultAfter = build.executor.run("assembleDebug")
        assertThat(resultAfter.exception).isNull()
    }

    private fun GradleProjectDefinition.addSharedDependency() {
        apply {
            dependencies {
                implementation(getSharedLibAsAar())
            }
        }
    }

    private fun GradleBuildResult.assertExceptionCause(kClass: KClass<*>, message: String) {
        var cause = exception?.cause
        while (cause != null) {
            if (cause.javaClass.canonicalName == kClass.java.canonicalName) {
                Truth.assertThat(cause.message?.trimMargin()).isEqualTo(message)
                return
            } else if (cause.cause != null) {
                cause = cause.cause
            } else {
                throw AssertionError("Cannot assert for exception type ${kClass}.")
            }
        }
    }
}
