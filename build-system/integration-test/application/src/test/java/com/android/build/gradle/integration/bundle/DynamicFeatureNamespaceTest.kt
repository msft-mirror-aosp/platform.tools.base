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

package com.android.build.gradle.integration.bundle

import com.android.SdkConstants.ANDROID_MANIFEST_XML
import com.android.SdkConstants.ATTR_PACKAGE
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.options.BooleanOption
import com.android.utils.XmlUtils
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.readText

class DynamicFeatureNamespaceTest {

    @get:Rule
    val rule = GradleRule.from {
        androidJavaApplication {
            android {
                defaultConfig {
                    applicationId = "com.example.test"
                }
                dynamicFeatures.add(DEFAULT_FEATURE_PATH)
            }
        }
        androidFeature {
            android {
                namespace = "com.example.test.feature"
            }

            dependencies {
                implementation(project(DEFAULT_APP_PATH))
            }
        }
    }

    @Test
    fun `intermediate feature manifest should have feature's namespace as package`() {
        val build = rule.build

        build.executor.run(":feature:processManifestDebugForFeature")

        val manifestFile =
            build.androidFeature().intermediatesDir
                .resolve("metadata_feature_manifest/debug/processManifestDebugForFeature/$ANDROID_MANIFEST_XML")

        val document =
            XmlUtils.parseDocument(manifestFile.readText(), false)
        Truth.assertThat(document.documentElement.hasAttribute(ATTR_PACKAGE)).isTrue()
        Truth.assertThat(document.documentElement.getAttribute(ATTR_PACKAGE))
            .isEqualTo("com.example.test.feature")
    }

    @Test
    fun `app manifest should have applicationId as package`() {
        val build = rule.build

        build.executor
            .with(BooleanOption.ENFORCE_UNIQUE_PACKAGE_NAMES, true)
            .run(":app:assembleDebug")

        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            applicationId().isEqualTo("com.example.test")
        }
    }
}
