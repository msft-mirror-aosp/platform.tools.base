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

package com.android.build.gradle.integration.common.fixture.project.options

import com.android.build.gradle.integration.common.fixture.project.TestEnvironment
import com.android.build.gradle.integration.common.utils.SdkHelper
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory

/**
 * Android SDK configuration for [GradleBuild] via [GradleRuleBuilder]
 */
interface SdkConfigurationBuilder {
    fun sdkDir(value: Path): SdkConfigurationBuilder
    fun removeSdk(): SdkConfigurationBuilder

    fun ndkVersion(value: String): SdkConfigurationBuilder
}

data class SdkConfiguration(
    /** if null, sdk should not be setup */
    val sdkDir: Path?,
    /** path for the NDK to be use as `ndkPath` in the DSL */
    val ndkPath: Path?,
)

internal class SdkConfigurationDelegate : SdkConfigurationBuilder,
    MergeableOptions<SdkConfigurationDelegate> {

    private var sdkDir: Path? = null
    private var useSdk = true

    private var ndkPath: Path? = null
    private var ndkVersion: String? = null


    override fun sdkDir(value: Path): SdkConfigurationBuilder {
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

    internal val asSdkConfiguration: SdkConfiguration
        get()  {
            val sdk = if (!useSdk) {
                null
            } else {
                sdkDir ?: SdkHelper.findSdkDir().toPath()
            }

            return SdkConfiguration(
                sdk,
                ndkPath ?: computeNdkPath(sdk),
            )
        }

    override fun mergeWith(other: SdkConfigurationDelegate) {
        if (!other.useSdk) {
            removeSdk()
        } else {
            other.sdkDir?.let {
                sdkDir = it
            }
        }

        other.ndkVersion?.let {
            ndkVersion = it
        }
    }

    private fun computeNdkPath(sdkDir: Path?): Path? {
        val envCustomAndroidNdkHome = Strings.emptyToNull(System.getenv()["CUSTOM_ANDROID_NDK_ROOT"]);

        return if (envCustomAndroidNdkHome != null) {
            Paths.get(envCustomAndroidNdkHome).also {
                Preconditions.checkState(
                    it.isDirectory(),
                    "CUSTOM_ANDROID_NDK_ROOT must point to a directory, %s is not a directory",
                    it.toString()
                );
            }
        } else {
            val version = ndkVersion
            TestEnvironment.getNdkPath(sdkDir, version)
        }
    }
}


