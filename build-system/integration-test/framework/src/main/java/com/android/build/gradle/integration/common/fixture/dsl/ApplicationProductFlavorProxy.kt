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

package com.android.build.gradle.integration.common.fixture.dsl

import com.android.build.api.dsl.ApkSigningConfig
import com.android.build.api.dsl.ApplicationProductFlavor

/**
 * Implemented manually due to the conflict between [setDimension] and [dimension] that breaks
 * the normal Java Proxy feature (class is considered broken)
 */
@Suppress("OVERRIDE_DEPRECATION")
class ApplicationProductFlavorProxy(
    dslRecorder: DslRecorder
): ProductFlavorProxy(dslRecorder), ApplicationProductFlavor {

    override var isDefault: Boolean
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.setBoolean("isDefault", value, usingIsNotation = true)
        }
    override var applicationId: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("applicationId", value)
        }
    override var versionCode: Int?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("versionCode", value)
        }
    override var versionName: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("versionName", value)
        }
    override var targetSdk: Int?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("targetSdk", value)
        }

    override fun targetSdkVersion(targetSdkVersion: Int) {
        dslRecorder.call("targetSdkVersion", listOf(targetSdkVersion), isVarArgs = false)
    }

    override fun targetSdkVersion(targetSdkVersion: String?) {
        dslRecorder.call("targetSdkVersion", listOf(targetSdkVersion), isVarArgs = false)
    }

    override var targetSdkPreview: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("targetSdkPreview", value)
        }

    override fun setTargetSdkVersion(targetSdkVersion: String?) {
        dslRecorder.call("setTargetSdkVersion", listOf(targetSdkVersion), isVarArgs = false)
    }

    override var maxSdk: Int?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("maxSdk", value)
        }

    override fun maxSdkVersion(maxSdkVersion: Int) {
        dslRecorder.call("maxSdkVersion", listOf(maxSdkVersion), isVarArgs = false)
    }

    override var applicationIdSuffix: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("applicationIdSuffix", value)
        }
    override var versionNameSuffix: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("versionNameSuffix", value)
        }
    override var multiDexEnabled: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("multiDexEnabled", value)
        }
    override var signingConfig: ApkSigningConfig?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            throw RuntimeException("Not yet supported")
        }
}
