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

@Suppress("OVERRIDE_DEPRECATION", "UNCHECKED_CAST")
class LibraryProductFlavorProxy(
contentHolder: DslContentHolder
): ProductFlavorProxy(contentHolder), LibraryProductFlavor {

    override var isDefault: Boolean
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.setBoolean("isDefault", value, usingIsNotation = true)
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

    override var multiDexEnabled: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("multiDexEnabled", value)
        }

    override val consumerProguardFiles: MutableList<File>
        get() = contentHolder.getList("consumerProguardFiles") as MutableList<File>

    override fun consumerProguardFile(proguardFile: Any): Any {
        contentHolder.call("consumerProguardFile", listOf(proguardFile), isVarArgs = false)
        return this
    }

    override fun consumerProguardFiles(vararg proguardFiles: Any): Any {
        contentHolder.call("consumerProguardFiles", listOf(proguardFiles), isVarArgs = true)
        return this
    }

    override var signingConfig: ApkSigningConfig?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            throw RuntimeException("Not yet supported")
        }

    override val aarMetadata: AarMetadata
        get() = contentHolder.chainedProxy("aarMetadata", AarMetadata::class.java)

    override fun aarMetadata(action: AarMetadata.() -> Unit) {
        contentHolder.runNestedBlock("aarMetadata", listOf(), aarMetadata::class.java) {
            action(this)
        }
    }
}
