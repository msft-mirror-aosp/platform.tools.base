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

@Suppress("OVERRIDE_DEPRECATION")
class ApplicationProductFlavorProxy(
    contentHolder: DslContentHolder
): ProductFlavorProxy(contentHolder), ApplicationProductFlavor {

    override var isDefault: Boolean
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.setBoolean("isDefault", value, usingIsNotation = true)
        }
    override var applicationId: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("applicationId", value)
        }
    override var versionCode: Int?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("versionCode", value)
        }
    override var versionName: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("versionName", value)
        }
    override var targetSdk: Int?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("targetSdk", value)
        }

    override fun targetSdkVersion(targetSdkVersion: Int) {
        contentHolder.call("targetSdkVersion", listOf(targetSdkVersion), isVarArgs = false)
    }

    override fun targetSdkVersion(targetSdkVersion: String?) {
        contentHolder.call("targetSdkVersion", listOf(targetSdkVersion), isVarArgs = false)
    }

    override var targetSdkPreview: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("targetSdkPreview", value)
        }

    override fun setTargetSdkVersion(targetSdkVersion: String?) {
        contentHolder.call("setTargetSdkVersion", listOf(targetSdkVersion), isVarArgs = false)
    }

    override var maxSdk: Int?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("maxSdk", value)
        }

    override fun maxSdkVersion(maxSdkVersion: Int) {
        contentHolder.call("maxSdkVersion", listOf(maxSdkVersion), isVarArgs = false)
    }

    override var applicationIdSuffix: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("applicationIdSuffix", value)
        }
    override var versionNameSuffix: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("versionNameSuffix", value)
        }
    override var multiDexEnabled: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("multiDexEnabled", value)
        }
    override var signingConfig: ApkSigningConfig?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            throw RuntimeException("Not yet supported")
        }
}
