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
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.internal.LoggerWrapper
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.options.BooleanOption
import com.android.ide.common.build.GenericBuiltArtifactsLoader
import com.android.testutils.apk.Apk
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
                defaultConfig.minSdk = 19
            }
            dependencies {}
        }
        privacySandboxSdk(":empty-privacy-sandbox-sdk", createMinimumProject = false) {
            android {
                compileSdk = DEFAULT_COMPILE_SDK_VERSION
                minSdk = 19
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
                    minSdk = 19
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
        val extractedPssApkFile = File(extractedPssApk)
        Apk(extractedPssApkFile).use { apk ->
            ApkSubject.assertThat(apk).exists()
            val manifest = ApkSubject.getManifestContent(extractedPssApkFile.toPath())
            Truth.assertThat(manifest.joinToString("\n")).isEqualTo("""
                N: android=http://schemas.android.com/apk/res/android (line=2)
                  E: manifest (line=2)
                    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=1
                    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="1.2.3" (Raw: "1.2.3")
                    A: http://schemas.android.com/apk/res/android:compileSdkVersion(0x01010572)=35
                    A: http://schemas.android.com/apk/res/android:compileSdkVersionCodename(0x01010573)="15" (Raw: "15")
                    A: package="com.example.emptyprivacysandboxsdk_10002" (Raw: "com.example.emptyprivacysandboxsdk_10002")
                    A: platformBuildVersionCode=35
                    A: platformBuildVersionName=15
                      E: uses-sdk (line=5)
                        A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=33
                        A: http://schemas.android.com/apk/res/android:targetSdkVersion(0x01010270)=35
                      E: uses-permission (line=13)
                        A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.emptyprivacysandboxsdk.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "com.example.emptyprivacysandboxsdk.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
                      E: application (line=15)
                        A: http://schemas.android.com/apk/res/android:appComponentFactory(0x0101057a)="androidx.core.app.CoreComponentFactory" (Raw: "androidx.core.app.CoreComponentFactory")
                          E: sdk-library (line=0)
                            A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.emptyprivacysandboxsdk" (Raw: "com.example.emptyprivacysandboxsdk")
                            A: http://schemas.android.com/apk/res/android:versionMajor(0x01010577)=10002
                          E: meta-data (line=0)
                            A: http://schemas.android.com/apk/res/android:name(0x01010003)="shadow.bundletool.com.android.vending.sdk.version.patch" (Raw: "shadow.bundletool.com.android.vending.sdk.version.patch")
                            A: http://schemas.android.com/apk/res/android:value(0x01010024)=3
                          E: property (line=0)
                            A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.sdksandbox.PROPERTY_SDK_PROVIDER_CLASS_NAME" (Raw: "android.sdksandbox.PROPERTY_SDK_PROVIDER_CLASS_NAME")
                            A: http://schemas.android.com/apk/res/android:value(0x01010024)="Test" (Raw: "Test")
                            """.trimIndent()
            )
            Truth.assertThat(apk.entries.map { it.toString() }).containsExactly(
                "/resources.arsc",
                "/res/layout-v21/notification_template_icon_group.xml",
                "/res/layout-v21/notification_template_custom_big.xml",
                "/res/layout-v21/notification_action_tombstone.xml",
                "/res/layout-v21/notification_action.xml",
                "/res/layout-v16/notification_template_custom_big.xml",
                "/res/layout/notification_template_part_time.xml",
                "/res/layout/notification_template_part_chronometer.xml",
                "/res/layout/notification_template_icon_group.xml",
                "/res/layout/notification_action_tombstone.xml",
                "/res/layout/notification_action.xml",
                "/res/layout/ime_secondary_split_test_activity.xml",
                "/res/layout/ime_base_split_test_activity.xml",
                "/res/layout/custom_dialog.xml",
                "/res/drawable-xxxhdpi-v4/ic_call_decline_low.png",
                "/res/drawable-xxxhdpi-v4/ic_call_decline.png",
                "/res/drawable-xxxhdpi-v4/ic_call_answer_video_low.png",
                "/res/drawable-xxxhdpi-v4/ic_call_answer_video.png",
                "/res/drawable-xxxhdpi-v4/ic_call_answer_low.png",
                "/res/drawable-xxxhdpi-v4/ic_call_answer.png",
                "/res/drawable-xxhdpi-v4/ic_call_decline_low.png",
                "/res/drawable-xxhdpi-v4/ic_call_decline.png",
                "/res/drawable-xxhdpi-v4/ic_call_answer_video_low.png",
                "/res/drawable-xxhdpi-v4/ic_call_answer_video.png",
                "/res/drawable-xxhdpi-v4/ic_call_answer_low.png",
                "/res/drawable-xxhdpi-v4/ic_call_answer.png",
                "/res/drawable-xhdpi-v4/notify_panel_notification_icon_bg.png",
                "/res/drawable-xhdpi-v4/notification_bg_normal_pressed.9.png",
                "/res/drawable-xhdpi-v4/notification_bg_normal.9.png",
                "/res/drawable-xhdpi-v4/notification_bg_low_pressed.9.png",
                "/res/drawable-xhdpi-v4/notification_bg_low_normal.9.png",
                "/res/drawable-xhdpi-v4/ic_call_decline_low.png",
                "/res/drawable-xhdpi-v4/ic_call_decline.png",
                "/res/drawable-xhdpi-v4/ic_call_answer_video_low.png",
                "/res/drawable-xhdpi-v4/ic_call_answer_video.png",
                "/res/drawable-xhdpi-v4/ic_call_answer_low.png",
                "/res/drawable-xhdpi-v4/ic_call_answer.png",
                "/res/drawable-v21/notification_action_background.xml",
                "/res/drawable-mdpi-v4/notify_panel_notification_icon_bg.png",
                "/res/drawable-mdpi-v4/notification_bg_normal_pressed.9.png",
                "/res/drawable-mdpi-v4/notification_bg_normal.9.png",
                "/res/drawable-mdpi-v4/notification_bg_low_pressed.9.png",
                "/res/drawable-mdpi-v4/notification_bg_low_normal.9.png",
                "/res/drawable-mdpi-v4/ic_call_decline_low.png",
                "/res/drawable-mdpi-v4/ic_call_decline.png",
                "/res/drawable-mdpi-v4/ic_call_answer_video_low.png",
                "/res/drawable-mdpi-v4/ic_call_answer_video.png",
                "/res/drawable-mdpi-v4/ic_call_answer_low.png",
                "/res/drawable-mdpi-v4/ic_call_answer.png",
                "/res/drawable-ldpi-v4/ic_call_decline_low.png",
                "/res/drawable-ldpi-v4/ic_call_decline.png",
                "/res/drawable-ldpi-v4/ic_call_answer_video_low.png",
                "/res/drawable-ldpi-v4/ic_call_answer_video.png",
                "/res/drawable-ldpi-v4/ic_call_answer_low.png",
                "/res/drawable-ldpi-v4/ic_call_answer.png",
                "/res/drawable-hdpi-v4/notify_panel_notification_icon_bg.png",
                "/res/drawable-hdpi-v4/notification_oversize_large_icon_bg.png",
                "/res/drawable-hdpi-v4/notification_bg_normal_pressed.9.png",
                "/res/drawable-hdpi-v4/notification_bg_normal.9.png",
                "/res/drawable-hdpi-v4/notification_bg_low_pressed.9.png",
                "/res/drawable-hdpi-v4/notification_bg_low_normal.9.png",
                "/res/drawable-hdpi-v4/ic_call_decline_low.png",
                "/res/drawable-hdpi-v4/ic_call_decline.png",
                "/res/drawable-hdpi-v4/ic_call_answer_video_low.png",
                "/res/drawable-hdpi-v4/ic_call_answer_video.png",
                "/res/drawable-hdpi-v4/ic_call_answer_low.png",
                "/res/drawable-hdpi-v4/ic_call_answer.png",
                "/res/drawable-anydpi-v21/ic_call_decline_low.xml",
                "/res/drawable-anydpi-v21/ic_call_decline.xml",
                "/res/drawable-anydpi-v21/ic_call_answer_video_low.xml",
                "/res/drawable-anydpi-v21/ic_call_answer_video.xml",
                "/res/drawable-anydpi-v21/ic_call_answer_low.xml",
                "/res/drawable-anydpi-v21/ic_call_answer.xml",
                "/res/drawable/notification_tile_bg.xml",
                "/res/drawable/notification_icon_background.xml",
                "/res/drawable/notification_bg_low.xml",
                "/res/drawable/notification_bg.xml",
                "/kotlin/reflect/reflect.kotlin_builtins",
                "/kotlin/ranges/ranges.kotlin_builtins",
                "/kotlin/kotlin.kotlin_builtins",
                "/kotlin/internal/internal.kotlin_builtins",
                "/kotlin/coroutines/coroutines.kotlin_builtins",
                "/kotlin/collections/collections.kotlin_builtins",
                "/kotlin/annotation/annotation.kotlin_builtins",
                "/classes.dex",
                "/META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory",
                "/META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler",
                "/META-INF/kotlinx_coroutines_core.version",
                "/META-INF/kotlinx_coroutines_android.version",
                "/META-INF/androidx.versionedparcelable_versionedparcelable.version",
                "/META-INF/androidx.tracing_tracing.version",
                "/META-INF/androidx.startup_startup-runtime.version",
                "/META-INF/androidx.savedstate_savedstate.version",
                "/META-INF/androidx.profileinstaller_profileinstaller.version",
                "/META-INF/androidx.privacysandbox.sdkruntime_sdkruntime-core.version",
                "/META-INF/androidx.privacysandbox.sdkruntime_sdkruntime-client.version",
                "/META-INF/androidx.lifecycle_lifecycle-viewmodel.version",
                "/META-INF/androidx.lifecycle_lifecycle-viewmodel-savedstate.version",
                "/META-INF/androidx.lifecycle_lifecycle-runtime.version",
                "/META-INF/androidx.lifecycle_lifecycle-livedata-core.version",
                "/META-INF/androidx.interpolator_interpolator.version",
                "/META-INF/androidx.core_core.version",
                "/META-INF/androidx.core_core-ktx.version",
                "/META-INF/androidx.arch.core_core-runtime.version",
                "/META-INF/androidx.annotation_annotation-experimental.version",
                "/META-INF/androidx.activity_activity.version",
                "/META-INF/androidx/privacysandbox/tools/tools/LICENSE.txt",
                "/DebugProbesKt.bin",
                "/AndroidManifest.xml"
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
