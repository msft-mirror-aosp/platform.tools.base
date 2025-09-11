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
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox.privacySandboxSampleProjectWithDynamicFeature
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class PrivacySandboxAppWithDynamicFeatureApkOutputTest {

    @get:Rule
    val rule = privacySandboxSampleProjectWithDynamicFeature()

    private fun GradleBuild.configuredExecutor() = executor
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
        .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
        .withPerTestPrefsRoot(true)
        .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_REQUIRE_SERVICES, false)

    @Test
    fun getApkOutputForAppWithDynamicFeature() {
        val build = rule.build {
            androidApplication(":example-app") {
                pluginCallbacks += ViaBundleVerificationForAppCallback::class.java
            }
        }
        build.configuredExecutor().run(":example-app:fetchApks")
    }

    @Test
    fun getApkOutputForDynamicFeature() {
        val build = rule.build {
            androidFeature(":feature") {
                pluginCallbacks += VerificationForDynamicFeatureCallback::class.java
            }
        }
        build.configuredExecutor().run(":feature:fetchApks")
    }

    class VerificationForDynamicFeatureCallback: FetchTaskForFeatureCallback<FetchApkDynamicFeatureVerificationTask>() {
        override val taskType: Class<FetchApkDynamicFeatureVerificationTask>
            get() = FetchApkDynamicFeatureVerificationTask::class.java
        override val privacySandboxEnabledApkOutputProperty: (FetchApkDynamicFeatureVerificationTask) -> Property<ApkOutput>
            get() = FetchApkDynamicFeatureVerificationTask::privacySandboxEnabledApkOutput
        override val privacySandboxDisabledApkOutputProperty: (FetchApkDynamicFeatureVerificationTask) -> Property<ApkOutput>
            get() = FetchApkDynamicFeatureVerificationTask::privacySandboxDisabledApkOutput
    }
}

abstract class FetchApkDynamicFeatureVerificationTask: FetchApkTask() {
    @TaskAction
    fun execute() {
        privacySandboxEnabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(4)
            checkGroupFiles(0, "standalone.apk")
            checkGroupDescription(0, "Source Sdk: com.example.privacysandboxsdk_10002")
            checkGroupFiles(1, "standalone.apk")
            checkGroupDescription(1, "Source Sdk: com.example.privacysandboxsdkb_10002")
            checkGroupFiles(2, "base-master_3.apk")
            checkGroupDescription(2, "Apks from Main Bundle")
            checkGroupFiles(3, "feature-debug.apk")
            checkGroupDescription(3, "Dynamic feature Apk Group")
        }

        privacySandboxDisabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(2)
            checkGroupFiles(0, "base-master_2.apk", "comexampleprivacysandboxsdk-master.apk", "comexampleprivacysandboxsdkb-master.apk")
            checkGroupFiles(1, "feature-debug.apk")
            checkGroupDescription(1, "Dynamic feature Apk Group")
        }
    }
}

