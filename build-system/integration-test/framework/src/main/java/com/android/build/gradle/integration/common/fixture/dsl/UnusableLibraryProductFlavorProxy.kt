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

import com.android.build.api.dsl.AarMetadata
import com.android.build.api.dsl.ApkSigningConfig
import com.android.build.api.dsl.LibraryProductFlavor
import java.io.File

/**
 * Implemented manually due to the conflict between [setDimension] and [dimension] that breaks
 * the normal Java Proxy feature (class is considered broken)
 */
@Suppress("OVERRIDE_DEPRECATION", "UNCHECKED_CAST")
class UnusableLibraryProductFlavorProxy(
): UnusableProductFlavorProxy(), LibraryProductFlavor {

    override var isDefault: Boolean
        get() = throwUnusableError("LibraryProductFlavor.isDefault")
        set(value) {
            throwUnusableError("LibraryProductFlavor.isDefault")
        }

    override var multiDexEnabled: Boolean?
        get() = throwUnusableError("LibraryProductFlavor.multiDexEnabled")
        set(value) {
            throwUnusableError("LibraryProductFlavor.multiDexEnabled")
        }

    override val consumerProguardFiles: MutableList<File>
        get() = throwUnusableError("LibraryProductFlavor.consumerProguardFiles")

    override fun consumerProguardFile(proguardFile: Any): Any {
        throwUnusableError("LibraryProductFlavor.consumerProguardFile")
    }

    override fun consumerProguardFiles(vararg proguardFiles: Any): Any {
        throwUnusableError("LibraryProductFlavor.consumerProguardFiles")
    }

    override var signingConfig: ApkSigningConfig?
        get() = throwUnusableError("LibraryProductFlavor.signingConfig")
        set(value) {
            throwUnusableError("LibraryProductFlavor.signingConfig")
        }

    override val aarMetadata: AarMetadata
        get() = throwUnusableError("LibraryProductFlavor.aarMetadata")

    override fun aarMetadata(action: AarMetadata.() -> Unit) {
        throwUnusableError("LibraryProductFlavor.aarMetadata")
    }
}
