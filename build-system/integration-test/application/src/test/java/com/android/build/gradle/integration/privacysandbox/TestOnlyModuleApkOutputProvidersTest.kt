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

package com.android.build.gradle.integration.privacysandbox

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.fixture.testprojects.createGradleProjectBuilder
import com.android.build.gradle.integration.privacysandbox.PrivacySandboxTestOnlyModuleApkOutputTest.Companion.getBuildFileContentWithFetchTask
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.JavaVersion
import org.junit.Rule
import org.junit.Test

class TestOnlyModuleApkOutputProvidersTest {

    @JvmField
    @Rule
    val project =  createGradleProjectBuilder {
        withKotlinPlugin = true
        subProject(":app") {
            plugins.add(PluginType.ANDROID_APP)
            android {
                defaultCompileSdk()
                minSdk = 23
                namespace = "com.example.privacysandboxsdk.consumer"
            }
            addFile(
                "src/main/java/com/privacysandboxsdk/consumer/HelloWorld.kt",
                // language=kotlin
                """
                package com.example.privacysandboxsdk.consumer

                class HelloWorld {

                    fun printSomething() {
                        // The line below should compile if classes from another SDK are in the
                        // same compile classpath.
                        println("Hello World!")
                    }
                }
            """.trimIndent()
            )

        }
        subProject(":app-test") {
            plugins.add(PluginType.ANDROID_TEST)
            plugins.add(PluginType.KOTLIN_ANDROID)
            android {
                defaultCompileSdk()
                minSdk = 23
                namespace = "com.example.privacysandboxsdk.consumer.test"
                targetProjectPath = ":app"
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions {
                    jvmTarget = "17"
                }
            }
            addFile("src/main/java/com/privacysandboxsdk/consumer/test/HelloWorldTest.kt",
                """
                package com.example.privacysandboxsdk.consumer.test

                class HelloWorldTest {

                    fun testSomething() {
                        println(1+1)
                    }
                }

                """.trimIndent())
        }
    }.enableProfileOutput().create()

    private fun executor() = project.executor()
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
        .withPerTestPrefsRoot(true)
        .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679

    @Test
    fun getApkOutput() {
        project.getSubproject(":app-test").buildFile.appendText(
            getBuildFileContentWithFetchTask("""
                    def apkInstall = getPrivacySandboxEnabledApkOutput().get().apkInstallGroups
                    if (apkInstall.size() != 2 || apkInstall[0].apks.size() != 1 || apkInstall[1].apks.size() != 1) {
                        throw new GradleException("Unexpected number of apks")
                    }
                    assert apkInstall[0].apks.any { it.getAsFile().name.contains("app-debug.apk") }

                    assert apkInstall[1].apks.any { it.getAsFile().name.contains("app-test-debug.apk") }
                    assert apkInstall[1].description.contains("Testing Apk")

                    apkInstall = getPrivacySandboxDisabledApkOutput().get().apkInstallGroups
                    if (apkInstall.size() != 2 || apkInstall[0].apks.size() != 1 || apkInstall[1].apks.size() != 1) {
                        throw new GradleException("Unexpected number of apks")
                    }
                    assert apkInstall[0].apks.any { it.getAsFile().name.contains("app-debug.apk") }
                    assert apkInstall[1].apks.any { it.getAsFile().name.contains("app-test-debug.apk") }
                    assert apkInstall[1].description.contains("Testing Apk")

            """.trimIndent()

            )
        )
        executor()
            .with(BooleanOption.SKIP_APKS_VIA_BUNDLE_IF_POSSIBLE, true)
            .run(":app-test:fetchApks")
    }
}
