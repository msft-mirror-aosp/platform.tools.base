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

import com.android.build.api.variant.ApkOutput
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.JavaVersion
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class TestOnlyModuleVariantApkOutputProvidersTest {

    @get:Rule
    val rule =  GradleRule.configure().withProfileOutput()
        .withProfileOutput()
        .from {
            androidApplication {
                applyPlugin(PluginType.KOTLIN_ANDROID)
                android {
                    namespace = "com.example.privacysandboxsdk.consumer"
                    defaultConfig.minSdk = 23
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                }
                legacyKotlin {
                    jvmTarget = "17"
                }
                files.add(
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
            androidTest(":app-test", createMinimumProject = false) {
                applyPlugin(PluginType.KOTLIN_ANDROID)
                android {
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                    namespace = "com.example.privacysandboxsdk.consumer.test"
                    defaultConfig.minSdk = 23
                    targetProjectPath = ":app"
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                }
                legacyKotlin {
                    jvmTarget = "17"
                }
                files {
                    setupMinimumManifest()
                    add(
                        "src/main/java/com/privacysandboxsdk/consumer/test/HelloWorldTest.kt",
                        //language=kotlin
                        """
                            package com.example.privacysandboxsdk.consumer.test
                            class HelloWorldTest {
                                fun testSomething() {
                                    println(1+1)
                                }
                            }
                        """.trimIndent()
                    )
                }
            }
        }

    private fun GradleBuild.configuredExecutor() = executor
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
        .withPerTestPrefsRoot(true)
        .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679


    @Test
    fun getApkOutput() {
        val build = rule.build {
            androidTest(":app-test") {
                pluginCallbacks += VerificationForTestCallback::class.java
            }
        }

        build.configuredExecutor()
            .with(BooleanOption.SKIP_APKS_VIA_BUNDLE_IF_POSSIBLE, true)
            .run(":app-test:fetchApks")
    }

    class VerificationForTestCallback: FetchTaskForAndroidTestCallback<TestOnlyVerificationTask>() {
        override val taskType: Class<TestOnlyVerificationTask>
            get() = TestOnlyVerificationTask::class.java
        override val privacySandboxEnabledApkOutputProperty: (TestOnlyVerificationTask) -> Property<ApkOutput>
            get() = TestOnlyVerificationTask::privacySandboxEnabledApkOutput
        override val privacySandboxDisabledApkOutputProperty: (TestOnlyVerificationTask) -> Property<ApkOutput>
            get() = TestOnlyVerificationTask::privacySandboxDisabledApkOutput
    }
}

abstract class TestOnlyVerificationTask: FetchApkTask() {
    @TaskAction
    fun execute() {
        privacySandboxEnabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(2)
            checkGroupFiles(0, "app-debug.apk")
            checkGroupFiles(1, "app-test-debug.apk")
            checkGroupDescription(1, "Testing Apk")
        }

        privacySandboxDisabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(2)
            checkGroupFiles(0, "app-debug.apk")
            checkGroupFiles(1, "app-test-debug.apk")
            checkGroupDescription(1, "Testing Apk")
        }
    }
}
