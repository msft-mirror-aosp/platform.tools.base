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
import com.android.build.api.dsl.MaxSdkSpec
import com.android.build.api.dsl.TargetSdkSpec

/**
 * Implemented manually due to the conflict between [setDimension] and [dimension] that breaks
 * the normal Java Proxy feature (class is considered broken)
 */
@Suppress("OVERRIDE_DEPRECATION")
class UnusableApplicationProductFlavorProxy(
): UnusableProductFlavorProxy(), ApplicationProductFlavor {

    override var isDefault: Boolean
        get() = throwUnusableError("ApplicationProductFlavor.isDefault")
        set(value) {
            throwUnusableError("ApplicationProductFlavor.isDefault")
        }
    override var applicationId: String?
        get() = throwUnusableError("ApplicationProductFlavor.applicationId")
        set(value) {
            throwUnusableError("ApplicationProductFlavor.applicationId")
        }
    override var versionCode: Int?
        get() = throwUnusableError("ApplicationProductFlavor.versionCode")
        set(value) {
            throwUnusableError("ApplicationProductFlavor.versionCode")
        }
    override var versionName: String?
        get() = throwUnusableError("ApplicationProductFlavor.versionName")
        set(value) {
            throwUnusableError("ApplicationProductFlavor.versionName")
        }
    override var targetSdk: Int?
        get() = throwUnusableError("ApplicationProductFlavor.targetSdk")
        set(value) {
            throwUnusableError("ApplicationProductFlavor.targetSdk")
        }

    override fun targetSdk(action: TargetSdkSpec.() -> Unit) {
        throwUnusableError("ApplicationProductFlavor.targetSdk")
    }

    override fun targetSdkVersion(targetSdkVersion: Int) {
        throwUnusableError("ApplicationProductFlavor.targetSdkVersion")
    }

    override fun targetSdkVersion(targetSdkVersion: String?) {
        throwUnusableError("ApplicationProductFlavor.targetSdkVersion")
    }

    override var targetSdkPreview: String?
        get() = throwUnusableError("ApplicationProductFlavor.targetSdkPreview")
        set(value) {
            throwUnusableError("ApplicationProductFlavor.targetSdkPreview")
        }

    override fun setTargetSdkVersion(targetSdkVersion: String?) {
        throwUnusableError("ApplicationProductFlavor.setTargetSdkVersion")
    }

    override var maxSdk: Int?
        get() = throwUnusableError("ApplicationProductFlavor.maxSdk")
        set(value) {
            throwUnusableError("ApplicationProductFlavor.maxSdk")
        }

    override fun maxSdk(action: MaxSdkSpec.() -> Unit) {
        throwUnusableError("ApplicationProductFlavor.maxSdk")
    }

    override fun maxSdkVersion(maxSdkVersion: Int) {
        throwUnusableError("ApplicationProductFlavor.maxSdkVersion")
    }

    override var applicationIdSuffix: String?
        get() = throwUnusableError("ApplicationProductFlavor.applicationIdSuffix")
        set(value) {
            throwUnusableError("ApplicationProductFlavor.applicationIdSuffix")
        }
    override var versionNameSuffix: String?
        get() = throwUnusableError("ApplicationProductFlavor.versionNameSuffix")
        set(value) {
            throwUnusableError("ApplicationProductFlavor.versionNameSuffix")
        }
    override var multiDexEnabled: Boolean?
        get() = throwUnusableError("ApplicationProductFlavor.multiDexEnabled")
        set(value) {
            throwUnusableError("ApplicationProductFlavor.multiDexEnabled")
        }
    override var signingConfig: ApkSigningConfig?
        get() = throwUnusableError("ApplicationProductFlavor.signingConfig")
        set(value) {
            throwUnusableError("ApplicationProductFlavor.signingConfig")
        }
}
