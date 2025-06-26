/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.manageddevice.application

import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomAndroidSdk
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomSdkDir
import com.android.build.gradle.integration.manageddevice.utils.simpleGMDProject
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class UtpManagedDeviceSetupTest {

    @get:Rule
    val customAndroidSdkRule = CustomAndroidSdkRule()

    @get:Rule
    val project1 = GradleRule.configure()
        .withCustomSdkDir(customAndroidSdkRule)
        .from(folderName = "project1") { simpleGMDProject() }

    @get:Rule
    val project2 = GradleRule.configure()
        .withCustomSdkDir(customAndroidSdkRule)
        .from(folderName = "project2") { simpleGMDProject() }

    private val executors: List<GradleTaskExecutor> by lazy {
        listOf(
            project1.build.executor.withCustomAndroidSdk(customAndroidSdkRule),
            project2.build.executor.withCustomAndroidSdk(customAndroidSdkRule),
        )
    }

    @Test
    fun setupSingleDevice() {
        executors[0].run(":app:cleanManagedDevices")
        executors[0].run(":app:device1Setup")
    }

    @Test
    fun setupTwoIdenticalManagedDeviceInParallel() {
        assertThat(executors).hasSize(2)
        assertThat(project1.getMainBuildDirectory().toString())
            .isNotEqualTo(project2.getMainBuildDirectory().toString())

        executors.parallelStream().forEach {
            it.run(":app:cleanManagedDevices")
        }
        executors.parallelStream().forEach {
            it.run(":app:device1Setup")
        }
    }
}
