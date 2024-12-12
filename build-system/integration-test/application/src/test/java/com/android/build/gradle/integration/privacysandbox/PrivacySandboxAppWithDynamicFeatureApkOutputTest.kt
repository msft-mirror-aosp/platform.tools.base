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
import com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox.privacySandboxSampleProjectWithDynamicFeature
import com.android.build.gradle.integration.privacysandbox.PrivacySandboxDefaultApkOutputTest.Companion.getBuildFileContentWithFetchTaskForAppVariant
import com.android.build.gradle.integration.privacysandbox.PrivacySandboxDefaultApkOutputTest.Companion.getBuildFileContentWithFetchTaskForDynamicFeatureVariant
import com.android.build.gradle.integration.privacysandbox.PrivacySandboxDefaultApkOutputTest.Companion.viaBundleVerificationString
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

class PrivacySandboxAppWithDynamicFeatureApkOutputTest {

    @JvmField
    @Rule
    val project = privacySandboxSampleProjectWithDynamicFeature()

    private fun executor() = project.executor()
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
        .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
        .withPerTestPrefsRoot(true)
        .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_REQUIRE_SERVICES, false)

    @Test
    fun getApkOutputForAppWithDynamicFeature() {
        project.getSubproject("example-app").buildFile.appendText(
            getBuildFileContentWithFetchTaskForAppVariant(viaBundleVerificationString)
        )

        executor().run(":example-app:fetchApks")
    }

    @Test
    fun getApkOutputForDynamicFeature() {
        project.getSubproject("feature").buildFile.appendText(
            getBuildFileContentWithFetchTaskForDynamicFeatureVariant(
                """
                    def apkInstall = getPrivacySandboxEnabledApkOutput().get().apkInstallGroups
                    if (apkInstall.size() != 4 || apkInstall[0].apks.size() != 1 || apkInstall[1].apks.size() != 1
                        || apkInstall[2].apks.size() != 1 || apkInstall[3].apks.size() != 1) {
                        throw new GradleException("Unexpected number of apks")
                    }
                    assert apkInstall[0].apks.first().getAsFile().name.contains("standalone.apk")
                    assert apkInstall[0].description.contains("Source Sdk: com.example.privacysandboxsdk_10002")

                    assert apkInstall[1].apks.first().getAsFile().name.contains("standalone.apk")
                    assert apkInstall[1].description.contains("Source Sdk: com.example.privacysandboxsdkb_10002")

                    assert apkInstall[2].apks.first().asFile.name.contains("base-master_3.apk")
                    assert apkInstall[2].description.contains("Apks from Main Bundle")

                    assert apkInstall[3].apks.any { it.getAsFile().name.contains("feature-debug.apk") }
                    assert apkInstall[3].description.contains("Dynamic feature Apk Group")

                    apkInstall = getPrivacySandboxDisabledApkOutput().get().apkInstallGroups
                    if (apkInstall.size() != 2 || apkInstall[0].apks.size() != 3 || apkInstall[1].apks.size() != 1) {
                        throw new GradleException("Unexpected number of apks")
                    }
                    assert apkInstall[0].apks.any { it.getAsFile().name.contains("base-master_2.apk") }
                    assert apkInstall[0].apks.any { it.getAsFile().absolutePath.contains("comexampleprivacysandboxsdk-master.apk") }
                    assert apkInstall[0].apks.any { it.getAsFile().absolutePath.contains("comexampleprivacysandboxsdkb-master.apk") }

                    assert apkInstall[1].apks.any { it.getAsFile().name.contains("feature-debug.apk") }
                    assert apkInstall[1].description.contains("Dynamic feature Apk Group")
        """.trimIndent()
            )
        )

        executor().run(":feature:fetchApks")
    }

}
