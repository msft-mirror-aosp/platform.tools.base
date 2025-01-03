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

import com.android.build.api.variant.ApkInstallGroup
import com.android.build.api.variant.ApkOutput
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.DeviceSpec
import com.android.build.api.variant.DynamicFeatureAndroidComponentsExtension
import com.android.build.api.variant.GeneratesApk
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.DynamicFeatureComponentCallback
import com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox.privacySandboxSampleProject
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.junit.Rule
import org.junit.Test

/**
 * Basic task for verification of apk output with and without privacy sandbox enabled.
 *
 * this does not actually verify anything. Each callback provide its own task that extend this
 * to do specific verification
 */
abstract class FetchApkTask: DefaultTask() {
    @get:Internal
    abstract val privacySandboxEnabledApkOutput: Property<ApkOutput>

    @get:Internal
    abstract val privacySandboxDisabledApkOutput: Property<ApkOutput>

    /**
     * Verifies how many [ApkInstallGroup] are supposed to be there.
     */
    protected fun List<ApkInstallGroup>.checkGroupCount(expected: Int) {
        if (this.size != expected) {
            throw RuntimeException("Expected $expected install groups but found ${this.size}")
        }
    }

    /**
     * Verifies the description of a specific [ApkInstallGroup] in the given list.
     *
     * The group is selected via `groupIndex` in order to provide better error messaging.
     */
    protected fun List<ApkInstallGroup>.checkGroupDescription(
        groupIndex: Int,
        expected: String) {
        val group = this[groupIndex]
        if (group.description != expected) {
            throw RuntimeException("Expected description: '$expected' for group index $groupIndex, but was: '${group.description}'")
        }
    }

    /**
     * Verifies the files (by filename) of a specific [ApkInstallGroup] in the given list.
     *
     * The group is selected via `groupIndex` in order to provide better error messaging.
     *
     * `expectedFilenames` contains the list of file names that are expected to be found in the
     * installation group.
     */
    protected fun List<ApkInstallGroup>.checkGroupFiles(
        groupIndex: Int,
        vararg expectedFilenames: String) {
        val group = this[groupIndex]
        val actualNames = group.apks.map { it.asFile.name }.sorted()
        if (expectedFilenames.size != actualNames.size) {
            throw RuntimeException("Group $groupIndex has wrong size. Expected ${expectedFilenames.size} but got the following list: $actualNames")
        }

        val expectedNames = expectedFilenames.toList().sorted()
        for (index in expectedNames.indices) {
            if (actualNames[index] != expectedNames[index]) {
                throw RuntimeException("Group $groupIndex, expected file ${expectedFilenames[index]} at index $index but got ${actualNames[index]}. Full list is $actualNames instead of $expectedNames")
            }
        }
    }
}

/**
 * Verification when going through the Bundle to get the APK
 */
abstract class FetchApkViaBundleVerificationTask: FetchApkTask() {
    @TaskAction
    fun execute() {
        privacySandboxEnabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(3)
            checkGroupFiles(0, "standalone.apk")
            checkGroupDescription(0, "Source Sdk: com.example.privacysandboxsdk_10002")
            checkGroupFiles(1, "standalone.apk")
            checkGroupDescription(1, "Source Sdk: com.example.privacysandboxsdkb_10002")
            checkGroupFiles(2, "base-master_3.apk")
            checkGroupDescription(2, "Apks from Main Bundle")
        }

       privacySandboxDisabledApkOutput.get().apkInstallGroups.apply {
           checkGroupCount(1)
           checkGroupFiles(
               0,
               "base-master_2.apk",
               "comexampleprivacysandboxsdk-master.apk",
               "comexampleprivacysandboxsdkb-master.apk"
           )
       }
    }
}

/**
 * Verification when not going through the bundle to get the APK.
 */
abstract class FetchApkSkippingAppBundleVerificationTask: FetchApkTask() {
    @TaskAction
    fun execute() {
        privacySandboxEnabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(3)
            checkGroupFiles(0, "standalone.apk")
            checkGroupDescription(0, "Source Sdk: com.example.privacysandboxsdk_10002")
            checkGroupFiles(1, "standalone.apk")
            checkGroupDescription(1, "Source Sdk: com.example.privacysandboxsdkb_10002")
            checkGroupFiles(2, "example-app-debug.apk", "example-app-debug-injected-privacy-sandbox.apk")
        }

        privacySandboxDisabledApkOutput.get().apkInstallGroups.apply {
            checkGroupCount(1)
            checkGroupFiles(
                0,
                "example-app-debug.apk",
                "comexampleprivacysandboxsdk-master.apk",
                "comexampleprivacysandboxsdkb-master.apk",
                "example-app-debug-injected-privacy-sandbox-compat.apk"
            )
        }
    }
}

/**
 * Base class handling setting up a [FetchApkTask] task, if given an instance of [GeneratesApk].
 *
 * This is meant to be extended by a callback that provides the variant via `handleExtension`
 */
abstract class BaseVariantCallbackForApk<TaskT: FetchApkTask> {
    abstract val privacySandboxEnabledApkOutputProperty: (TaskT) -> Property<ApkOutput>
    abstract val privacySandboxDisabledApkOutputProperty: (TaskT) -> Property<ApkOutput>

    /**
     * Wires a task (of a type extending [FetchApkTask]), to the variant's ApkOutputProviders
     * API.
     *
     * The task will do verification of the result of the API.
     */
    protected fun wireTaskToOutputProvider(variant: GeneratesApk, taskProvider: TaskProvider<TaskT>) {
        variant.outputProviders.provideApkOutputToTask(
            taskProvider,
            privacySandboxEnabledApkOutputProperty,
            DeviceSpec.Builder()
                .setName("testDevice")
                .setApiLevel(34)
                .setCodeName("")
                .setAbis(listOf())
                .setSupportsPrivacySandbox(true)
                .build()
        )
        variant.outputProviders.provideApkOutputToTask(
            taskProvider,
            privacySandboxDisabledApkOutputProperty,
            DeviceSpec.Builder()
                .setName("testDevice")
                .setApiLevel(34)
                .setCodeName("")
                .setAbis(listOf())
                .setSupportsPrivacySandbox(false)
                .build())
    }
}

/**
 * Base class for all Application callback with any FetchApkTask.
 */
abstract class FetchTaskForAppCallback<TaskT: FetchApkTask>: BaseVariantCallbackForApk<TaskT>(), ApplicationComponentCallback {
    abstract val taskType: Class<TaskT>

    override fun handleExtension(
        project: Project,
        androidComponents: ApplicationAndroidComponentsExtension
    ) {
        // register the task that will do the verification of the APK install group content.
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

/**
 * App callback using FetchApkViaBundleVerificationTask
 */
class ViaBundleVerificationForAppCallback: FetchTaskForAppCallback<FetchApkViaBundleVerificationTask>() {
    override val taskType: Class<FetchApkViaBundleVerificationTask>
        get() = FetchApkViaBundleVerificationTask::class.java
    override val privacySandboxEnabledApkOutputProperty: (FetchApkViaBundleVerificationTask) -> Property<ApkOutput>
        get() = FetchApkViaBundleVerificationTask::privacySandboxEnabledApkOutput
    override val privacySandboxDisabledApkOutputProperty: (FetchApkViaBundleVerificationTask) -> Property<ApkOutput>
        get() = FetchApkViaBundleVerificationTask::privacySandboxDisabledApkOutput
}

/**
 * App callback using FetchApkSkipApkViaBundleVerificationTask
 */
class SkipApkViaBundleVerificationForAppCallback: FetchTaskForAppCallback<FetchApkSkippingAppBundleVerificationTask>() {
    override val taskType: Class<FetchApkSkippingAppBundleVerificationTask>
        get() = FetchApkSkippingAppBundleVerificationTask::class.java
    override val privacySandboxEnabledApkOutputProperty: (FetchApkSkippingAppBundleVerificationTask) -> Property<ApkOutput>
        get() = FetchApkSkippingAppBundleVerificationTask::privacySandboxEnabledApkOutput
    override val privacySandboxDisabledApkOutputProperty: (FetchApkSkippingAppBundleVerificationTask) -> Property<ApkOutput>
        get() = FetchApkSkippingAppBundleVerificationTask::privacySandboxDisabledApkOutput
}


/**
 * Base class for all AndroidTest callback (on application) with any FetchApkTask.
 */
abstract class FetchTaskForAppAndroidTestCallback<TaskT: FetchApkTask>: BaseVariantCallbackForApk<TaskT>(), ApplicationComponentCallback {
    abstract val taskType: Class<TaskT>

    override fun handleExtension(
        project: Project,
        androidComponents: ApplicationAndroidComponentsExtension
    ) {
        val taskProvider = project.tasks.register(
            "fetchApks",
            taskType
        )

        androidComponents.apply {
            onVariants(selector().withName("debug")) { variant ->
                val androidTest = variant.androidTest
                    ?: throw RuntimeException("AndroidTest component not found for debug variant of ${project.path}")

                wireTaskToOutputProvider(androidTest, taskProvider)
            }
        }
    }
}

/**
 * Base class for all Dynamic Feature callback with any FetchApkTask.
 */
abstract class FetchTaskForFeatureCallback<TaskT: FetchApkTask>: BaseVariantCallbackForApk<TaskT>(), DynamicFeatureComponentCallback {
    abstract val taskType: Class<TaskT>

    override fun handleExtension(
        project: Project,
        androidComponents: DynamicFeatureAndroidComponentsExtension
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

class PrivacySandboxDefaultApkOutputTest {

    @get:Rule
    val rule = privacySandboxSampleProject()

    private fun executor() = rule.build.executor
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
        .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
        .withPerTestPrefsRoot(true)
        .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_REQUIRE_SERVICES, false)

    @Test
    fun getApkOutput() {
        rule.build {
            androidApplication(":example-app") {
                pluginCallbacks += SkipApkViaBundleVerificationForAppCallback::class.java
            }
        }

        executor()
            .with(BooleanOption.SKIP_APKS_VIA_BUNDLE_IF_POSSIBLE, true)
            .run(":example-app:fetchApks")
    }

    @Test
    fun getViaBundleApkOutput() {
        rule.build {
            androidApplication(":example-app") {
                pluginCallbacks += ViaBundleVerificationForAppCallback::class.java
            }
        }

        executor().run(":example-app:fetchApks")
    }
}
