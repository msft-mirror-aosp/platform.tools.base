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
import com.android.build.api.dsl.TestProductFlavor

class UnusableTestProductFlavorProxy(
): UnusableProductFlavorProxy(), TestProductFlavor {

    override var targetSdk: Int?
        get() = throwUnusableError("TestProductFlavor.targetSdk")
        set(value) {
            throwUnusableError("TestProductFlavor.targetSdk")
        }

    override fun targetSdkVersion(targetSdkVersion: Int) {
        throwUnusableError("TestProductFlavor.targetSdkVersion")
    }

    override fun targetSdkVersion(targetSdkVersion: String?) {
        throwUnusableError("TestProductFlavor.targetSdkVersion")
    }

    override var targetSdkPreview: String?
        get() = throwUnusableError("TestProductFlavor.targetSdkPreview")
        set(value) {
            throwUnusableError("TestProductFlavor.targetSdkPreview")
        }

    override fun setTargetSdkVersion(targetSdkVersion: String?) {
        throwUnusableError("TestProductFlavor.setTargetSdkVersion")
    }

    override var maxSdk: Int?
        get() = throwUnusableError("TestProductFlavor.maxSdk")
        set(value) {
            throwUnusableError("TestProductFlavor.maxSdk")
        }

    override fun maxSdkVersion(maxSdkVersion: Int) {
        throwUnusableError("TestProductFlavor.maxSdkVersion")
    }

    override var multiDexEnabled: Boolean?
        get() = throwUnusableError("TestProductFlavor.multiDexEnabled")
        set(value) {
            throwUnusableError("TestProductFlavor.multiDexEnabled")
        }
    override var signingConfig: ApkSigningConfig?
        get() = throwUnusableError("TestProductFlavor.signingConfig")
        set(value) {
            throwUnusableError("TestProductFlavor.signingConfig")
        }
}
