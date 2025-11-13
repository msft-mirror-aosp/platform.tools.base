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

package com.android.build.gradle.integration.connected.application

import com.android.SdkConstants
import com.android.build.api.variant.impl.BuiltArtifactsImpl
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import com.android.build.gradle.integration.common.output.ApkSubject
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.ide.common.build.GenericBuiltArtifactsLoader
import com.android.testutils.truth.PathSubject
import com.android.utils.NullLogger
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

class InstallProfilesPerDeviceApiConnectedTest {
    companion object {
        @JvmField
        @ClassRule
        val emulator = getEmulator()
    }

    @get:Rule
    val rule = GradleRule.fromProject(BasicSpec()) {
        androidApplication(":app") {
            android {
                defaultConfig {
                    minSdk {
                        version = release(27)
                    }
                    targetSdk {
                        version = release(28)
                    }
                }

                signingConfigs {
                    create("myConfig") {
                        it.storeFile = projectDotFile("../debug.keystore")
                        it.storePassword = "android"
                        it.keyAlias = "androiddebugkey"
                        it.keyPassword = "android"
                    }
                }

                buildTypes {
                    named("release") {
                        it.signingConfig = signingConfigs.getByName("myConfig")
                    }
                }
            }
            files {
                add("src/main/baselineProfiles/file.txt",
                    """
                        HSPLcom/google/Foo;->mainMethod(II)I
                        HSPLcom/google/Foo;->mainMethod-name-with-hyphens(II)I
                    """.trimIndent()
                )
                add("src/release/baselineProfiles/file.txt",
                    """
                        HSPLcom/google/Foo;->releaseMethod(II)I
                        HSPLcom/google/Foo;->releaseMethod-name-with-hyphens(II)I
                    """.trimIndent()
                )
            }
        }
    }

    @Test
    fun `install base baseline profile`() {
        val build = rule.build
        val app = build.androidApplication(":app")

        val result = build.executor.run("assembleRelease", "installRelease")

        val dexMetadataProperties = app
            .intermediatesDir
            .resolve(
                "${InternalArtifactType.DEX_METADATA_DIRECTORY.getFolderName()}/release/compileReleaseArtProfile/${SdkConstants.FN_DEX_METADATA_PROP}"
            )

        PathSubject.assertThat(dexMetadataProperties).contentWithUnixLineSeparatorsIsExactly(
            """
                31=0/.dm
                2147483647=0/.dm
                28=1/.dm
                29=1/.dm
                30=1/.dm
            """.trimIndent()
        )

        result.assertOutputContains("Installing APK 'app-release.apk, app-release.dm'")

        // Validate that renamed baseline profile file is present
        val renamedBaselineProfile= app
            .outputsDir
            .resolve(
                "${SdkConstants.EXT_ANDROID_PACKAGE}/release/${SdkConstants.FN_OUTPUT_BASELINE_PROFILES}/0/app-release.dm"
            )
        PathSubject.assertThat(renamedBaselineProfile).exists()

        // Validate that baseline profile is in app metadata file
        val appMetadataJson= app
            .outputsDir
            .resolve(
                "${SdkConstants.EXT_ANDROID_PACKAGE}/release/${BuiltArtifactsImpl.METADATA_FILE_NAME}"
            )
        PathSubject.assertThat(appMetadataJson).apply {
            contains(SdkConstants.FN_OUTPUT_BASELINE_PROFILES)
            contains("app-release.dm")
        }

        val builtArtifacts = GenericBuiltArtifactsLoader.loadFromFile(
            appMetadataJson.toFile(),
            NullLogger()
        )
        val baselineProfileFile =
            builtArtifacts?.baselineProfiles?.lastOrNull()?.baselineProfileFiles?.firstOrNull()
        Truth.assertThat(baselineProfileFile).isEqualTo(renamedBaselineProfile.toFile())
    }

    @Test
    fun `install baseline profile with splits`() {
        val build = rule.build {
            androidApplication(":app") {
                android {
                    splits {
                        abi {
                            isEnable = true
                            reset()
                            include("x86", "x86_64")
                            isUniversalApk = false
                        }

                    }
                }
            }
        }
        val app = build.androidApplication(":app")

        val result = build.executor.run("assembleRelease", "installRelease")

        val dexMetadataProperties = app
            .intermediatesDir
            .resolve(
                "${InternalArtifactType.DEX_METADATA_DIRECTORY.getFolderName()}/release/compileReleaseArtProfile/${SdkConstants.FN_DEX_METADATA_PROP}"
            )

        PathSubject.assertThat(dexMetadataProperties).contentWithUnixLineSeparatorsIsExactly(
                """
                31=0/.dm
                2147483647=0/.dm
                28=1/.dm
                29=1/.dm
                30=1/.dm
            """.trimIndent()
        )

        result.assertOutputContains("Installing APK 'app-x86_64-release.apk, app-x86_64-release.dm'")

        // Validate that renamed baseline profile file is present
        val renamedBaselineProfile= app
            .outputsDir
            .resolve(
                "${SdkConstants.EXT_ANDROID_PACKAGE}/release/${SdkConstants.FN_OUTPUT_BASELINE_PROFILES}/0/app-x86_64-release.dm"
            )
        PathSubject.assertThat(renamedBaselineProfile).exists()

        // Validate that baseline profile is in app metadata file
        val appMetadataJson= app
            .outputsDir
            .resolve(
                "${SdkConstants.EXT_ANDROID_PACKAGE}/release/${BuiltArtifactsImpl.METADATA_FILE_NAME}"
            )
        PathSubject.assertThat(appMetadataJson).apply {
            contains(SdkConstants.FN_OUTPUT_BASELINE_PROFILES)
            contains("app-x86-release.dm")
            contains("app-x86_64-release.dm")
        }

        val builtArtifacts = GenericBuiltArtifactsLoader.loadFromFile(
            appMetadataJson.toFile(),
            NullLogger()
        )
        val baselineProfileFile =
            builtArtifacts?.baselineProfiles?.lastOrNull()?.baselineProfileFiles?.firstOrNull()
        Truth.assertThat(baselineProfileFile).isEqualTo(renamedBaselineProfile.toFile())
    }

    @Test
    fun validateOptOut() {
        val build = rule.build {
            androidApplication(":app") {
                files.add(
                    "src/main/baseline-prof.txt",
                    """
                        HSPLcom/google/Foo;->mainMethod(II)I
                        HSPLcom/google/Foo;->mainMethod-name-with-hyphens(II)I
                    """.trimIndent()
                )
            }
        }
        val app = build.androidApplication(":app")

        val result = build.executor.run("assembleRelease")

        val dexMetadataProperties = app
            .intermediatesDir
            .resolve(
                "${InternalArtifactType.DEX_METADATA_DIRECTORY.getFolderName()}/release/compileReleaseArtProfile/${SdkConstants.FN_DEX_METADATA_PROP}"
            )
        PathSubject.assertThat(dexMetadataProperties).exists()

        app.reconfigure {
            android {
                installation {
                    enableBaselineProfile = true
                }
            }
        }

        build.executor.run("clean", "assembleRelease")
        PathSubject.assertThat(dexMetadataProperties).exists()

        app.reconfigure {
            android {
                installation {
                    enableBaselineProfile = false
                }
            }
        }

        build.executor.run("clean", "assembleRelease")
        PathSubject.assertThat(dexMetadataProperties).doesNotExist()
    }

    @Test
    fun validateConfigurationCacheUsed() {
        val build = rule.build
        val app = build.androidApplication(":app")

        // Run twice to verify configuration cache compatibility
        build.executor.run("clean", "assembleRelease")
        val result = build.executor.run("clean", "assembleRelease")
        result.assertOutputContains("Configuration cache entry reused.")

        // Validate that renamed baseline profile file is present
        val renamedBaselineProfile= app
            .outputsDir
            .resolve(
                "${SdkConstants.EXT_ANDROID_PACKAGE}/release/${SdkConstants.FN_OUTPUT_BASELINE_PROFILES}/0/app-release.dm"
            )
        PathSubject.assertThat(renamedBaselineProfile).exists()

        // Validate that baseline profile is in app metadata file
        val appMetadataJson= app
            .outputsDir
            .resolve(
                "${SdkConstants.EXT_ANDROID_PACKAGE}/release/${BuiltArtifactsImpl.METADATA_FILE_NAME}"
            )
        PathSubject.assertThat(appMetadataJson).apply {
            contains(SdkConstants.FN_OUTPUT_BASELINE_PROFILES)
            contains("app-release.dm")
        }
    }

    // Regression test for b/330593433
    @Test
    fun apkZipPackagingTest() {
        val build = rule.build {
            androidApplication(":app") {
                applyPlugin(PluginType.MAVEN_PUBLISH)
                android {
                    publishing {
                        singleVariant("release") {
                            publishApk()
                        }
                    }
                }
                pluginCallbacks += MavenPublishPluginCallback::class.java
            }
        }
        val app = build.androidApplication(":app")

        build.executor.run("publishAppPublicationToMavenRepository")

        val apkFile = app.buildDir.resolve("testRepo/test/basic/app/1.0/app-1.0.zip")
        PathSubject.assertThat(apkFile).isFile()

        ApkSubject.assertThat(apkFile) {
            javaResources().folder(SdkConstants.FN_OUTPUT_BASELINE_PROFILES).hasSize(2)
        }
    }

    class MavenPublishPluginCallback: GenericCallback {
        override fun handleProject(project: Project) {
            project.extensions.getByType(PublishingExtension::class.java).apply {
                publications.register("app", MavenPublication::class.java) { publication ->
                    publication.groupId = "test.basic"
                    publication.artifactId = "app"
                    publication.version = "1.0"

                    repositories { repo ->
                        repo.maven {
                            it.url = project.uri(project.projectDir.resolve("build/testRepo"))
                        }
                    }
                    project.afterEvaluate {
                        publication.from(project.components.getByName("release"))
                    }
                }
            }
        }
    }


    // This test is disabled and should only be run locally with an API level lower than 28
    //@Test
    fun apiLevelNotSupportedForBaselineProfile() {
        val build = rule.build {
            androidApplication(":app") {
                files.add(
                    "src/main/baseline-prof.txt",
                    """
                        HSPLcom/google/Foo;->mainMethod(II)I
                        HSPLcom/google/Foo;->mainMethod-name-with-hyphens(II)I
                    """.trimIndent()

                )
            }
        }

        val result = build.executor.run("assembleRelease", "installRelease")

        result.assertOutputContains("Baseline Profile not found for API level ")
    }
}
