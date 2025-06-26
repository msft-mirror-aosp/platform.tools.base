/*
 * Copyright (C) 2023 The Android Open Source Project
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
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox.privacySandboxSdkLibraryProject
import com.android.build.gradle.integration.common.output.ApkSubject
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.internal.LoggerWrapper
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.options.BooleanOption
import com.android.ide.common.build.GenericBuiltArtifactsLoader
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import java.io.File

class PrivacySandboxSdkMinimalTest {

    @get:Rule
    val rule = GradleRule.from {
        privacySandboxSdkLibraryProject(":androidlib3") {
            android {
                namespace = "com.example.androidlib3"
                defaultConfig.minSdk = 21
            }
            dependencies {}
        }
        privacySandboxSdk(":empty-privacy-sandbox-sdk", createMinimumProject = false) {
            android {
                compileSdk = DEFAULT_COMPILE_SDK_VERSION
                minSdk = 21
                bundle {
                    applicationId = "com.example.emptyprivacysandboxsdk"
                    sdkProviderClassName = "Test"
                    setVersion(1, 2, 3)
                }
            }
            dependencies {
                include(project(":androidlib3"))
            }
        }
        androidApplication(":minimal-app", createMinimumProject = false) {
            android {
                namespace = "com.example.emptyprivacysandboxsdk.consumer"
                compileSdk = DEFAULT_COMPILE_SDK_VERSION
                defaultConfig {
                    minSdk = 21
                    versionCode = 1
                }
            }
            files.setupMinimumManifest()
            dependencies {
                implementation(project(":empty-privacy-sandbox-sdk"))
            }
        }
        gradleProperties {
            add(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
            add(BooleanOption.USE_ANDROID_X, true)
        }
    }

    private fun GradleBuild.configuredExecutor() = executor
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_ENABLE_LINT, true)
        .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
        .withPerTestPrefsRoot(true)
        .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679

    @Test
    fun privacySandboxWithMinimalConfigAndDependency() {
        val build = rule.build

        build.configuredExecutor().run(":minimal-app:buildPrivacySandboxSdkApksForDebug")
        val ideModelFile = build.androidApplication(":minimal-app")
            .resolve(InternalArtifactType.EXTRACTED_APKS_FROM_PRIVACY_SANDBOX_SDKs_IDE_MODEL)
            .resolve("debug/buildPrivacySandboxSdkApksForDebug/ide_model.json")
            .toFile()

        val extractedPssApk = GenericBuiltArtifactsLoader.loadListFromFile(ideModelFile,
            LoggerWrapper.getLogger(PrivacySandboxSdkMinimalTest::class.java))
            .single { it.applicationId == "com.example.emptyprivacysandboxsdk_10002" }
            .elements.single().outputFile
        ApkSubject.assertThat(File(extractedPssApk)) {
            manifest().isEqualTo("""
                N: android=http://schemas.android.com/apk/res/android
                  E: manifest
                    A: http://schemas.android.com/apk/res/android:versionCode=1
                    A: http://schemas.android.com/apk/res/android:versionName="1.2.3"
                    A: http://schemas.android.com/apk/res/android:compileSdkVersion=$DEFAULT_COMPILE_SDK_VERSION
                    A: http://schemas.android.com/apk/res/android:compileSdkVersionCodename="16"
                    A: package="com.example.emptyprivacysandboxsdk_10002"
                    A: platformBuildVersionCode=$DEFAULT_COMPILE_SDK_VERSION
                    A: platformBuildVersionName=16
                      E: uses-sdk
                        A: http://schemas.android.com/apk/res/android:minSdkVersion=33
                        A: http://schemas.android.com/apk/res/android:targetSdkVersion=36
                      E: uses-permission
                        A: http://schemas.android.com/apk/res/android:name="com.example.emptyprivacysandboxsdk.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
                      E: application
                        A: http://schemas.android.com/apk/res/android:appComponentFactory="androidx.core.app.CoreComponentFactory"
                          E: sdk-library
                            A: http://schemas.android.com/apk/res/android:name="com.example.emptyprivacysandboxsdk"
                            A: http://schemas.android.com/apk/res/android:versionMajor=10002
                          E: meta-data
                            A: http://schemas.android.com/apk/res/android:name="shadow.bundletool.com.android.vending.sdk.version.patch"
                            A: http://schemas.android.com/apk/res/android:value=3
                          E: property
                            A: http://schemas.android.com/apk/res/android:name="android.sdksandbox.PROPERTY_SDK_PROVIDER_CLASS_NAME"
                            A: http://schemas.android.com/apk/res/android:value="Test"
            """.trimIndent())

            javaResources().containsExactly(
                "kotlin/reflect/reflect.kotlin_builtins",
                "kotlin/ranges/ranges.kotlin_builtins",
                "kotlin/kotlin.kotlin_builtins",
                "kotlin/internal/internal.kotlin_builtins",
                "kotlin/coroutines/coroutines.kotlin_builtins",
                "kotlin/collections/collections.kotlin_builtins",
                "kotlin/annotation/annotation.kotlin_builtins",
                "META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory",
                "META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler",
                "META-INF/kotlinx_coroutines_core.version",
                "META-INF/kotlinx_coroutines_android.version",
                "META-INF/androidx.versionedparcelable_versionedparcelable.version",
                "META-INF/androidx.tracing_tracing.version",
                "META-INF/androidx.startup_startup-runtime.version",
                "META-INF/androidx.savedstate_savedstate.version",
                "META-INF/androidx.profileinstaller_profileinstaller.version",
                "META-INF/androidx.privacysandbox.sdkruntime_sdkruntime-core.version",
                "META-INF/androidx.privacysandbox.sdkruntime_sdkruntime-client.version",
                "META-INF/androidx.lifecycle_lifecycle-viewmodel.version",
                "META-INF/androidx.lifecycle_lifecycle-viewmodel-savedstate.version",
                "META-INF/androidx.lifecycle_lifecycle-runtime.version",
                "META-INF/androidx.lifecycle_lifecycle-livedata-core.version",
                "META-INF/androidx.lifecycle_lifecycle-process.version",
                "META-INF/androidx.lifecycle_lifecycle-runtime.version",
                "META-INF/androidx.interpolator_interpolator.version",
                "META-INF/androidx.core_core.version",
                "META-INF/androidx.core_core-ktx.version",
                "META-INF/androidx.arch.core_core-runtime.version",
                "META-INF/androidx.annotation_annotation-experimental.version",
                "META-INF/androidx.activity_activity.version",
                "META-INF/androidx/privacysandbox/tools/tools/LICENSE.txt",
                "DebugProbesKt.bin",
            )
        }
    }


    @Test
    fun testAssemble() {
        val sdkServiceFile = "src/main/java/com/example/androidlib3/MySdk.kt"
        val build = rule.build {
            androidLibrary(":androidlib3") {
                files.add(
                    sdkServiceFile,
                    //language=kotlin
                    """
                        package com.example.androidlib3
                        import androidx.privacysandbox.tools.PrivacySandboxService
                        @PrivacySandboxService
                        interface MySdk {
                            suspend fun foo(bar: Int): String
                        }
                    """.trimIndent()
                )
            }
        }

        build.configuredExecutor().run(":minimal-app:assembleDebug")

        build.androidLibrary(":androidlib3").files.remove(sdkServiceFile)

        build.configuredExecutor().expectFailure().run(":minimal-app:assembleDebug").also {
            Truth.assertThat(it.failureMessage).contains(
                    "Unable to proceed generating shim with no provided sdk descriptor entries in: ")
        }
    }

    @Test
    fun checkPrivacySandboxOptInRequired() {
        val build = rule.build {
            gradleProperties {
                remove(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT)
            }
        }
        val result = build.executor
            .expectFailure()
            .run(":minimal-app:buildPrivacySandboxSdkApksForDebug")

        assertThat(result.stderr).contains(
                """
                    Privacy Sandbox SDK Plugin support must be explicitly enabled.
                    To enable support, add
                        android.experimental.privacysandboxsdk.plugin.enable=true
                    to your project's gradle.properties file.
                """.trimIndent()
        )
    }
}
