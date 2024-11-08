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

package com.android.build.gradle.integration.common.fixture.project

import com.android.SdkConstants
import com.android.build.gradle.integration.BazelIntegrationTestsSuite
import com.android.build.gradle.integration.common.utils.SdkHelper
import com.android.testutils.TestUtils
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import java.io.File

/**
 * Android SDK configuration for [GradleBuild] via [GradleRuleBuilder]
 */
interface SdkConfigurationBuilder {
    fun sdkDir(value: File): SdkConfigurationBuilder
    fun removeSdk(): SdkConfigurationBuilder

    fun ndkVersion(value: String): SdkConfigurationBuilder
}

data class SdkConfiguration(
    /** if null, sdk should not be setup */
    val sdkDir: File?,
    /** path for the NDK to be use as `ndkPath` in the DSL */
    val ndkPath: File?,
)

class SdkConfigurationDelegate: SdkConfigurationBuilder {
    private var sdkDir: File? = null
    private var useSdk = true

    private var ndkPath: File? = null
    private var ndkVersion: String? = null


    override fun sdkDir(value: File): SdkConfigurationBuilder {
        sdkDir = value
        return this
    }

    override fun removeSdk(): SdkConfigurationBuilder {
        useSdk = false
        return this
    }

    override fun ndkVersion(value: String): SdkConfigurationBuilder {
        ndkVersion = value
        return this
    }

    val asSdkConfiguration: SdkConfiguration
        get()  {
            val sdk = if (!useSdk) {
                null
            } else {
                sdkDir ?: SdkHelper.findSdkDir()
            }

            return SdkConfiguration(
                sdk,
                ndkPath ?: computeNdkPath(sdk),
            )
        }

    private fun computeNdkPath(sdkDir: File?): File? {
        val envCustomAndroidNdkHome = Strings.emptyToNull(System.getenv()["CUSTOM_ANDROID_NDK_ROOT"]);

        return if (envCustomAndroidNdkHome != null) {
            File(envCustomAndroidNdkHome).also {
                Preconditions.checkState(
                    it.isDirectory(),
                    "CUSTOM_ANDROID_NDK_ROOT must point to a directory, %s is not a directory",
                    it.absolutePath
                );
            }
        } else {
            val version = ndkVersion
            if (version != null) {
                if (TestUtils.runningFromBazel()) {
                    File(BazelIntegrationTestsSuite.NDK_SIDE_BY_SIDE_ROOT.toFile(), version)
                } else if (sdkDir != null) {
                    File(File(sdkDir, SdkConstants.FD_NDK_SIDE_BY_SIDE), version)
                } else {
                    null
                }
            } else {
                if (TestUtils.runningFromBazel()) {
                    BazelIntegrationTestsSuite.NDK_IN_TMP.toFile()
                } else if (sdkDir != null) {
                    File(sdkDir, SdkConstants.FD_NDK);
                } else {
                    null
                }
            }
        }
    }
}


