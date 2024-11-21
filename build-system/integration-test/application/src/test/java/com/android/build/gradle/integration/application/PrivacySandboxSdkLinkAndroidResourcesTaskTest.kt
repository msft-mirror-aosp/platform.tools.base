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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.internal.privaysandboxsdk.PrivacySandboxSdkInternalArtifactType
import com.android.build.gradle.internal.res.PrivacySandboxSdkLinkAndroidResourcesTask
import com.android.build.gradle.options.BooleanOption
import com.android.builder.core.ToolsRevisionUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import java.util.zip.ZipFile
import kotlin.io.path.isRegularFile

/** Test for [PrivacySandboxSdkLinkAndroidResourcesTask] */
internal class PrivacySandboxSdkLinkAndroidResourcesTaskTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withProperties {
            add(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
        }.from{
            androidLibrary(":androidLib1") {
                android {
                    namespace = "com.example.androidLib1"
                    defaultConfig.minSdk = 12
                }
                files {
                    add(
                        "src/main/res/values/strings.xml",
                        //language=xml
                        """
                            <resources>
                                <string name="string_from_androidLib1">androidLib1</string>
                                <string name="permission_name">androidLib1 permission</string>
                                <string name="permission_label">androidLib1 label</string>
                            </resources>
                        """.trimIndent()
                    )

                    add(
                        "src/main/res/layout/layout.xml",
                        //language=xml
                        """
                            <?xml version="1.0" encoding="utf-8"?>
                            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                                android:layout_width="match_parent"
                                android:layout_height="match_parent">

                                <TextView
                                    android:id="@+id/string_from_androidLib1"
                                    android:layout_width="match_parent"
                                    android:layout_height="wrap_content"
                                    android:layout_weight="1"
                                    android:text="TextView" />
                            </LinearLayout>
                        """.trimIndent()
                    )
                    add(
                        "src/main/AndroidManifest.xml",
                        //language=xml
                        """
                            <?xml version="1.0" encoding="utf-8"?>
                            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                                      <permission
                                      android:name = "@string/permission_name"
                                      android:protectionLevel = "dangerous" />    <application />
                            </manifest>
                        """.trimIndent()
                    )
                }
            }
            privacySandboxSdk(":privacySdkSandbox1") {
                android {
                    buildToolsVersion = ToolsRevisionUtils.DEFAULT_BUILD_TOOLS_REVISION.toString()
                    minSdk = 19
                    bundle {
                        applicationId = "com.example.privacysandboxsdk"
                        sdkProviderClassName = "Test"
                        setVersion(major = 1, minor = 2, patch = 3)
                    }
                }
                dependencies {
                    include(project(":androidLib1"))
                }
            }
        }

    @Test
    fun testGeneratesLinkedBundledResources() {
        val build = rule.build
        build.executor.run(":privacySdkSandbox1:linkPrivacySandboxResources")

        val privacySandboxSdk = build.androidProject(":privacySdkSandbox1")
        val bundledResourcesFile = privacySandboxSdk.getIntermediateFile(
                PrivacySandboxSdkInternalArtifactType.LINKED_MERGE_RES_FOR_ASB.getFolderName(),
                "single",
                "linkPrivacySandboxResources",
                "bundled-res.ap_")
        assertThat(bundledResourcesFile.isRegularFile()).isTrue()
        ZipFile(bundledResourcesFile.toFile()).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertThat(
                    entries
            ).containsExactly(
                    "AndroidManifest.xml",
                    "res/layout/layout.xml",
                    "resources.pb"
            )
        }
    }
}
