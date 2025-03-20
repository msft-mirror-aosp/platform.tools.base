/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.BaseAndroidProject
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.internal.fusedlibrary.FusedLibraryInternalArtifactType.MERGED_MANIFEST
import com.android.build.gradle.internal.manifest.parseManifest
import com.android.build.gradle.options.BooleanOption
import com.android.builder.errors.EvalIssueException
import com.android.builder.errors.IssueReporter
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/* Tests for [FusedLibraryManifestMergerTask] */
internal class FusedLibraryManifestMergerTaskTest {
    @get:Rule
    val rule = GradleRule.configure()
        .withMavenRepository {
            aar("com.externaldep.externalaar")
                .withManifest(
                    // language=XML
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <manifest
                            xmlns:android="http://schemas.android.com/apk/res/android"
                            package="com.externaldep.externalaar">

                        <permission
                          android:name="com.externaldep.permission.REMOTE_PERMISSION"
                          android:label="@string/external_permission_label"
                          android:description="@string/external_permission_label" />
                        </manifest>
                    """.trimIndent()
                ).addResource(
                    "values/strings.xml",
                    // language=XML
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="string_from_external_lib">Remote String</string>
                            <string name='external_permission_label'>External label</string>
                            <string name='external_permission_description'>External description</string>
                        </resources>""".trimIndent()
                )
        }.from {
            // Library dependency at depth 1 with no dependencies.
            androidLibrary(":androidLib1") {
                android {
                    namespace = "com.example.androidLib1"
                    defaultConfig.minSdk = 12
                }
                files.update("src/main/AndroidManifest.xml").replaceWith(
                    //language=xml
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                            <uses-permission android:name="android.permission.SEND_SMS"/>
                            <intent-filter>
                                <data android:scheme="https" android:host="${'$'}{hostName}" />
                                <data android:scheme="https" android:host="${'$'}{notReplaced}" />
                            </intent-filter>
                        </manifest>
                    """.trimIndent()
                )
            }
            // Library dependency at depth 0 with a dependency on androidLib1.
            androidLibrary(":androidLib2") {
                android {
                    namespace = "com.example.androidLib2"
                    defaultConfig.minSdk = 19
                }
                dependencies {
                    implementation(project(":androidLib1"))
                }
            }
            // Library dependency at depth 0 with no dependencies
            androidLibrary(":androidLib3") {
                android {
                    namespace = "com.example.androidLib3"
                    defaultConfig.minSdk = 18
                }
                dependencies {
                    implementation(project(":androidLib1"))
                }
            }
            fusedLibrary(":fusedLib1") {
                androidFusedLibrary {
                    namespace = "com.example.fusedLib1"
                    minSdk = 19
                    manifestPlaceholders["hostName"] = "injected-value-for-hostName"
                }
                dependencies {
                    include(project(":androidLib3"))
                    include(project(":androidLib2"))
                    include(project(":androidLib1"))
                    include("com.externaldep.externalaar:externalaar:1.0")
                }
            }
            androidApplication {
                android {
                    namespace = "com.example.app"
                    defaultConfig.minSdk = 19
                }
                // Add a dependency on the fused library aar in the test if needed.
            }
            gradleProperties {
                add(BooleanOption.FUSED_LIBRARY_SUPPORT, true)
            }
        }

    @Test
    fun checkFusedLibraryManifest() {
        val build = rule.build
        val fusedLib1Project = build.fusedLibrary(":fusedLib1")

        build.executor.run(":fusedLib1:mergeManifest")

        val mergedManifestFile = fusedLib1Project.resolve(MERGED_MANIFEST)
            .resolve("single/mergeManifest/AndroidManifest.xml")
            .toFile()

        val parsedManifestFile =
                parseManifest(
                    manifestFileContent = mergedManifestFile.readText(),
                    manifestFilePath = mergedManifestFile.absolutePath,
                    manifestFileRequired = true,
                    issueReporter = manifestIssueDataReporter
                )

        assertThat(parsedManifestFile.packageName).isEqualTo("com.example.fusedLib1")
        assertThat(parsedManifestFile.minSdkVersion?.apiLevel).isEqualTo(19)
        assertThat(parsedManifestFile.targetSdkVersion).isNull()
        // Permission merged transitively androidLib1 -> androidLib2 -> fusedLib1
        val mergedManifestContents = mergedManifestFile.readText()
        assertThat(mergedManifestContents)
                .contains("<uses-permission android:name=\"android.permission.SEND_SMS\" />")
        checkManifestBlameLogIsCreated(fusedLib1Project)
        assertThat(mergedManifestContents)
                .contains("android:name=\"com.externaldep.permission.REMOTE_PERMISSION\"")
    }

    @Test
    fun failWhenLibraryMinSdkVersionConflictWithFusedLibrary() {
        val build = rule.build {
            androidLibrary(":androidLib3") {
                android {
                    defaultConfig.minSdk = 20
                }
            }
        }

        build.executor.expectFailure().run(":fusedLib1:mergeManifest").assertErrorContains(
            "uses-sdk:minSdkVersion 19 cannot be smaller than version 20 declared in library [:androidLib3]"
        )
    }

    @Test
    fun failWhenFusedLibraryMinSdkVersionConflictWithApp() {
        val build = rule.build {
            fusedLibrary(":fusedLib1") {
                androidFusedLibrary {
                    minSdk = 20
                }
            }
        }

        // build the fused AAR then get its location
        build.executor.run(":fusedLib1:assemble")
        val aarFile = build.fusedLibrary(":fusedLib1").getAarLocationForCopy(AarSelector.NO_BUILD_TYPE)

        // inject it as a local dependency of the app module
        build.androidApplication().reconfigure {
            dependencies {
                implementation(files(aarFile))
            }
        }

        build.executor.expectFailure().run(":app:processDebugMainManifest").assertErrorContains(
            "uses-sdk:minSdkVersion 19 cannot be smaller than version 20 declared in library [fusedLib1.aar]"
        )
    }

    @Test
    fun testAppManifestMergesFusedLibraryManifest() {
        val build = rule.build
        build.executor.run(":app:assembleDebug")
        val mergedManifest = build.androidApplication()
            .intermediatesDir
            .resolve("merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml")
           .toFile()

        val parsedManifest =
                parseManifest(
                    manifestFileContent = mergedManifest.readText(),
                    manifestFilePath = mergedManifest.absolutePath,
                    manifestFileRequired = true,
                    issueReporter = manifestIssueDataReporter
                )
        assertThat(parsedManifest.minSdkVersion?.apiLevel).isEqualTo(19)
        assertThat(parsedManifest.packageName).isEqualTo("com.example.app")
        assertThat(parsedManifest.targetSdkVersion?.apiLevel).isEqualTo(19)
    }

    @Test
    fun testManifestPlaceholders() {
        val build = rule.build
        build.executor.run(":fusedLib1:assemble")

        build.fusedLibrary(":fusedLib1").assertAar(AarSelector.NO_BUILD_TYPE) {
            manifest().apply {
                contains("""android:host="injected-value-for-hostName"""")
                contains("""android:host="${'$'}{notReplaced}"""")
            }
        }
    }

    private fun checkManifestBlameLogIsCreated(project: BaseAndroidProject<*>) {
        val outputDir = project.outputsDir
        val manifestBlame = outputDir.resolve("logs/manifest-merger-mergeManifest-report.txt")
        assertThat(manifestBlame.toFile().length()).isGreaterThan(0)
    }
}

private val manifestIssueDataReporter: IssueReporter = object : IssueReporter() {
    override fun reportIssue(
            type: Type,
            severity: Severity,
            exception: EvalIssueException) {
        if (severity === Severity.ERROR) {
            throw exception
        }
    }

    override fun hasIssue(type: Type): Boolean {
        return false
    }
}
