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
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.build.gradle.options.BooleanOption
import com.android.utils.XmlUtils
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.readText

class DynamicFeatureNamespaceTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication(":app") {
            android {
                defaultConfig {
                    applicationId = "com.example.test"
                }
                dynamicFeatures.add(":feature")
            }

            HelloWorldAndroid.setupJava(files)
        }
        androidFeature(":feature") {
            android {
                namespace = "com.example.test.feature"
            }

            HelloWorldAndroid.setupJava(files)

            dependencies {
                implementation(project(":app"))
            }
        }
    }

    @Test
    fun `intermediate feature manifest should have feature's namespace as package`() {
        val build = rule.build

        build.executor.run(":feature:processManifestDebugForFeature")

        val manifestFile =
            build.androidFeature(":feature")
                .getIntermediateFile(
                    "metadata_feature_manifest",
                    "debug",
                    "processManifestDebugForFeature",
                    ANDROID_MANIFEST_XML
                )

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

        build.androidApplication(":app").assertApk(ApkSelector.DEBUG) {
            hasApplicationId("com.example.test")
        }
    }
}
