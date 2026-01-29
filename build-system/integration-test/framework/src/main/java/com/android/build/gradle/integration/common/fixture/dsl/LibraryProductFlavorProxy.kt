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
 * Implemented manually due to the conflict between [setDimension] and [dimension] that breaks the normal Java Proxy feature (class is
 * considered broken)
 */
@Suppress("OVERRIDE_DEPRECATION", "UNCHECKED_CAST")
class LibraryProductFlavorProxy(dslRecorder: DslRecorder) : ProductFlavorProxy(dslRecorder), LibraryProductFlavor {

  override var isDefault: Boolean
    get() = throw RuntimeException("Not yet supported")
    set(value) {
      this@LibraryProductFlavorProxy.dslRecorder.setBoolean("isDefault", value, usingIsNotation = true)
    }

  override var multiDexEnabled: Boolean?
    get() = throw RuntimeException("Not yet supported")
    set(value) {
      this@LibraryProductFlavorProxy.dslRecorder.set("multiDexEnabled", value)
    }

  override val consumerProguardFiles: MutableList<File>
    get() = ListProxy<File>(this@LibraryProductFlavorProxy.dslRecorder.createChainedRecorder("consumerProguardFiles"))

  override fun consumerProguardFile(proguardFile: Any): Any {
    this@LibraryProductFlavorProxy.dslRecorder.call("consumerProguardFile", listOf(proguardFile), isVarArgs = false)
    return this
  }

  override fun consumerProguardFiles(vararg proguardFiles: Any): Any {
    this@LibraryProductFlavorProxy.dslRecorder.call("consumerProguardFiles", listOf(proguardFiles), isVarArgs = true)
    return this
  }

  override var signingConfig: ApkSigningConfig?
    get() = throw RuntimeException("Not yet supported")
    set(value) {
      throw RuntimeException("Not yet supported")
    }

  override val aarMetadata: AarMetadata
    get() =
      DslProxy.createProxy(
        AarMetadata::class.java,
        this@LibraryProductFlavorProxy.dslRecorder.createChainedRecorder("consumerProguardFiles"),
      )

  override fun aarMetadata(action: AarMetadata.() -> Unit) {
    this@LibraryProductFlavorProxy.dslRecorder.runNestedBlock(
      name = "aarMetadata",
      parameters = listOf(),
      instanceProvider = { DslProxy.createProxy(AarMetadata::class.java, it) },
    ) {
      action(this)
    }
  }
}
