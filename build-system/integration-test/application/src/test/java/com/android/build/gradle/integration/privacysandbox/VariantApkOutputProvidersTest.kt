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
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid

import com.android.build.gradle.options.BooleanOption
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class VariantApkOutputProvidersTest {
    @get:Rule
    val rule =  GradleRule.from {
        androidApplication {
            HelloWorldAndroid.setupJava(files)
        }
    }

    private fun executor() = rule.build.executor
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
        .withPerTestPrefsRoot(true)
        .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679

    class VerificationInAppCallback: FetchTaskForAppCallback<VAOPT_VerificationForAppTask>() {
        override val taskType: Class<VAOPT_VerificationForAppTask>
            get() = VAOPT_VerificationForAppTask::class.java
        override val privacySandboxEnabledApkOutputProperty: (VAOPT_VerificationForAppTask) -> Property<ApkOutput>
            get() = VAOPT_VerificationForAppTask::privacySandboxEnabledApkOutput
        override val privacySandboxDisabledApkOutputProperty: (VAOPT_VerificationForAppTask) -> Property<ApkOutput>
            get() = VAOPT_VerificationForAppTask::privacySandboxDisabledApkOutput
    }

    @Test
    fun getAppApkOutput() {
        rule.build {
            androidApplication {
                pluginCallbacks += VerificationInAppCallback::class.java
            }
        }

        executor()
            .with(BooleanOption.SKIP_APKS_VIA_BUNDLE_IF_POSSIBLE, true)
            .run("fetchApks")
    }

    class AndroidTestVerificationInAppCallback: FetchTaskForAppAndroidTestCallback<VAOPT_VerificationForAndroidTestTask>() {
        override val taskType: Class<VAOPT_VerificationForAndroidTestTask>
            get() = VAOPT_VerificationForAndroidTestTask::class.java
        override val privacySandboxEnabledApkOutputProperty: (VAOPT_VerificationForAndroidTestTask) -> Property<ApkOutput>
            get() = VAOPT_VerificationForAndroidTestTask::privacySandboxEnabledApkOutput
        override val privacySandboxDisabledApkOutputProperty: (VAOPT_VerificationForAndroidTestTask) -> Property<ApkOutput>
            get() = VAOPT_VerificationForAndroidTestTask::privacySandboxDisabledApkOutput
    }

    @Test
    fun getAndroidTestApkOutput() {
        rule.build {
            androidApplication {
                pluginCallbacks += AndroidTestVerificationInAppCallback::class.java
            }
        }

        executor()
            .with(BooleanOption.SKIP_APKS_VIA_BUNDLE_IF_POSSIBLE, true)
            .run("fetchApks")
    }

    class ViaBundleVerificationCallback: FetchTaskForAppCallback<VAOPT_ViaBundleVerificationTask>() {
        override val taskType: Class<VAOPT_ViaBundleVerificationTask>
            get() = VAOPT_ViaBundleVerificationTask::class.java
        override val privacySandboxEnabledApkOutputProperty: (VAOPT_ViaBundleVerificationTask) -> Property<ApkOutput>
            get() = VAOPT_ViaBundleVerificationTask::privacySandboxEnabledApkOutput
        override val privacySandboxDisabledApkOutputProperty: (VAOPT_ViaBundleVerificationTask) -> Property<ApkOutput>
            get() = VAOPT_ViaBundleVerificationTask::privacySandboxDisabledApkOutput
    }

    @Test
    fun getViaBundleApkOutput() {
        rule.build {
            androidApplication {
                pluginCallbacks += ViaBundleVerificationCallback::class.java
            }
        }

        executor().run("fetchApks")
    }
}

abstract class VAOPT_VerificationForAndroidTestTask: FetchApkTask() {
    @TaskAction
    fun execute() {
        privacySandboxEnabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(2)
            checkGroupFiles(0, "app-debug.apk")
            checkGroupFiles(1, "app-debug-androidTest.apk")
        }
        privacySandboxDisabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(2)
            checkGroupFiles(0, "app-debug.apk")
            checkGroupFiles(1, "app-debug-androidTest.apk")
        }
    }
}

abstract class VAOPT_VerificationForAppTask: FetchApkTask() {
    @TaskAction
    fun execute() {
        privacySandboxEnabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(1)
            checkGroupFiles( 0, "app-debug.apk")
        }

        privacySandboxDisabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(1)
            checkGroupFiles(0, "app-debug.apk")
        }
    }
}

abstract class VAOPT_ViaBundleVerificationTask: FetchApkTask() {
    @TaskAction
    fun execute() {
        privacySandboxEnabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(1)
            checkGroupFiles(0, "base-master_2.apk")
            checkGroupDescription(0,"Apks from Main Bundle")
        }

        privacySandboxDisabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(1)
            checkGroupFiles( 0, "base-master_2.apk")
        }
    }
}
