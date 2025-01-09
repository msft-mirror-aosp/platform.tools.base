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
import com.android.build.api.variant.TestAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.plugins.TestComponentCallback
import com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox.privacySandboxSampleProjectWithSeparateTest
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class PrivacySandboxTestOnlyModuleApkOutputTest {

    @get:Rule
    val rule = privacySandboxSampleProjectWithSeparateTest()

    private fun GradleBuild.configuredExecutor() = executor
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
        .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
        .withPerTestPrefsRoot(true)
        .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_REQUIRE_SERVICES, false)

    @Test
    fun getTestOnlyModuleApkOutput() {
        val build = rule.build {
            androidTest(":example-app-test") {
                pluginCallbacks += VerificationForTestCallback::class.java
            }
        }

        build.configuredExecutor().run(":example-app-test:fetchApks")
    }

    class VerificationForTestCallback: FetchTaskForAndroidTestCallback<PrivacySandBoxTestOnlyVerificationTask>() {
        override val taskType: Class<PrivacySandBoxTestOnlyVerificationTask>
            get() = PrivacySandBoxTestOnlyVerificationTask::class.java
        override val privacySandboxEnabledApkOutputProperty: (PrivacySandBoxTestOnlyVerificationTask) -> Property<ApkOutput>
            get() = PrivacySandBoxTestOnlyVerificationTask::privacySandboxEnabledApkOutput
        override val privacySandboxDisabledApkOutputProperty: (PrivacySandBoxTestOnlyVerificationTask) -> Property<ApkOutput>
            get() = PrivacySandBoxTestOnlyVerificationTask::privacySandboxDisabledApkOutput
    }
}

/**
 * Base class for all Test callback with any FetchApkTask.
 *
 * Specific [FetchApkTask] implementations are providing the actual verification steps
 */
abstract class FetchTaskForAndroidTestCallback<TaskT: FetchApkTask>: BaseVariantCallbackForApk<TaskT>(),
    TestComponentCallback {

    abstract val taskType: Class<TaskT>

    override fun handleExtension(
        project: Project,
        androidComponents: TestAndroidComponentsExtension
    ) {
        val taskProvider = project.tasks.register(
            "fetchApks",
            taskType
        )

        androidComponents.apply {
            onVariants(selector().withName("debug")) { variant ->
                wireTaskToOutputProvider(variant, taskProvider)
            }
        }
    }
}

abstract class PrivacySandBoxTestOnlyVerificationTask: FetchApkTask() {
    @TaskAction
    fun execute() {
        privacySandboxEnabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(4)
            checkGroupFiles(0, "standalone.apk")
            checkGroupDescription(0, "Source Sdk: com.example.privacysandboxsdk_10002")
            checkGroupFiles(1, "standalone.apk")
            checkGroupDescription(1, "Source Sdk: com.example.privacysandboxsdkb_10002")
            checkGroupFiles(
                2,
                "example-app-debug.apk",
                "example-app-debug-injected-privacy-sandbox.apk"
            )
            checkGroupFiles(3, "example-app-test-debug.apk")
            checkGroupDescription(3, "Testing Apk")
        }

        privacySandboxDisabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(2)
            checkGroupFiles(
                0,
                "example-app-debug.apk",
                "comexampleprivacysandboxsdk-master.apk",
                "comexampleprivacysandboxsdkb-master.apk",
                "example-app-debug-injected-privacy-sandbox-compat.apk"
            )
            checkGroupFiles(1, "example-app-test-debug.apk")
            checkGroupDescription(1, "Testing Apk")
        }
    }
}
