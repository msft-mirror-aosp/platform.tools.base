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
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.privacysandbox.PrivacySandboxDefaultApkOutputTest.Companion.getBuildFileContentWithFetchTaskForAppVariant
import com.android.build.gradle.integration.privacysandbox.PrivacySandboxDefaultApkOutputTest.Companion.getBuildFileContentWithFetchTaskForAndroidVariant

import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

class VariantApkOutputProvidersTest {
    @JvmField
    @Rule
    val project =  GradleTestProject.builder()
        .fromTestApp(HelloWorldApp.forPlugin("com.android.application"))
        .create()

    private fun executor() = project.executor()
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
        .withPerTestPrefsRoot(true)
        .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679

    @Test
    fun getAppApkOutput() {
        project.buildFile.appendText(getBuildFileContentWithFetchTaskForAppVariant(
            """
                    def apkInstall = getPrivacySandboxEnabledApkOutput().get().apkInstallGroups
                    if (apkInstall.size() != 1 || apkInstall[0].apks.size() != 1) {
                        throw new GradleException("Unexpected number of apks")
                    }
                    assert apkInstall[0].apks.any { it.getAsFile().name.contains("project-debug.apk") }
                    apkInstall = getPrivacySandboxDisabledApkOutput().get().apkInstallGroups
                    if (apkInstall.size() != 1 || apkInstall[0].apks.size() != 1) {
                        throw new GradleException("Unexpected number of apks")
                    }
                    assert apkInstall[0].apks.any { it.getAsFile().name.contains("project-debug.apk") }
        """.trimIndent()
        ))
        executor()
            .with(BooleanOption.SKIP_APKS_VIA_BUNDLE_IF_POSSIBLE, true)
            .run("fetchApks")
    }

    @Test
    fun getAndroidTestApkOutput() {
        project.buildFile.appendText(getBuildFileContentWithFetchTaskForAndroidVariant(
            """
                    def apkInstall = getPrivacySandboxEnabledApkOutput().get().apkInstallGroups
                    if (apkInstall.size() != 2 || apkInstall[0].apks.size() != 1 || apkInstall[1].apks.size() != 1) {
                        throw new GradleException("Unexpected number of apks")
                    }
                    assert apkInstall[0].apks.any { it.getAsFile().name.contains("project-debug.apk") }
                    assert apkInstall[1].apks.any { it.getAsFile().name.contains("project-debug-androidTest.apk") }
                    apkInstall = getPrivacySandboxDisabledApkOutput().get().apkInstallGroups
                    if (apkInstall.size() != 2 || apkInstall[0].apks.size() != 1 || apkInstall[1].apks.size() != 1) {
                        throw new GradleException("Unexpected number of apks")
                    }
                    assert apkInstall[0].apks.any { it.getAsFile().name.contains("project-debug.apk") }
                    assert apkInstall[1].apks.any { it.getAsFile().name.contains("project-debug-androidTest.apk") }
        """.trimIndent()
            ))
        executor()
            .with(BooleanOption.SKIP_APKS_VIA_BUNDLE_IF_POSSIBLE, true)
            .run("fetchApks")
    }

    @Test
    fun getViaBundleApkOutput() {
        project.buildFile.appendText(getBuildFileContentWithFetchTaskForAppVariant(
            """
                    def apkInstall = getPrivacySandboxEnabledApkOutput().get().apkInstallGroups
                    if (apkInstall.size() != 1 || apkInstall[0].apks.size() != 1) {
                        throw new GradleException("Unexpected number of apks")
                    }
                    assert apkInstall[0].apks.first().asFile.name.contains("base-master_2.apk")
                    assert apkInstall[0].description.contains("Apks from Main Bundle")

                    apkInstall = getPrivacySandboxDisabledApkOutput().get().apkInstallGroups
                    if (apkInstall.size() != 1 || apkInstall[0].apks.size() != 1) {
                        throw new GradleException("Unexpected number of apks")
                    }
                    assert apkInstall[0].apks.any { it.getAsFile().name.contains("base-master_2.apk") }
        """.trimIndent()
        ))
        executor()
            .run("fetchApks")
    }
}
