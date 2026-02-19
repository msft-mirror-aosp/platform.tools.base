/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.internal.dsl

import com.android.build.api.dsl.CompileSdkReleaseSpec
import com.android.build.api.dsl.CompileSdkSpec
import com.android.build.api.dsl.CompileSdkVersion
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory

internal open class SettingsCompileSdkSpecImpl @Inject constructor(private val objectFactory: ObjectFactory) : CompileSdkSpec {

  override var version: CompileSdkVersion? = null

  override fun release(version: Int, action: (CompileSdkReleaseSpec.() -> Unit)): CompileSdkVersion {
    val compileSdkReleaseSpec = objectFactory.newInstance(SettingsCompileSdkReleaseSpecImpl::class.java)
    action.invoke(compileSdkReleaseSpec)

    return SettingsCompileSdkVersionImpl(
      apiLevel = version,
      minorApiLevel = compileSdkReleaseSpec.minorApiLevel,
      sdkExtension = compileSdkReleaseSpec.sdkExtension,
    )
  }

  override fun release(version: Int): CompileSdkVersion {
    return SettingsCompileSdkVersionImpl(apiLevel = version)
  }

  fun release(version: Int, action: Action<CompileSdkReleaseSpec>): CompileSdkVersion {
    val compileSdkReleaseSpec = objectFactory.newInstance(SettingsCompileSdkReleaseSpecImpl::class.java)
    action.execute(compileSdkReleaseSpec)

    return SettingsCompileSdkVersionImpl(
      apiLevel = version,
      minorApiLevel = compileSdkReleaseSpec.minorApiLevel,
      sdkExtension = compileSdkReleaseSpec.sdkExtension,
    )
  }

  override fun preview(version: String): CompileSdkVersion {
    return SettingsCompileSdkVersionImpl(codeName = version)
  }

  override fun addon(vendor: String, name: String, version: Int): CompileSdkVersion {
    return SettingsCompileSdkVersionImpl(vendorName = vendor, addonName = name, apiLevel = version)
  }
}

internal data class SettingsCompileSdkVersionImpl(
  override val apiLevel: Int? = null,
  override val minorApiLevel: Int? = null,
  override val sdkExtension: Int? = null,
  override val codeName: String? = null,
  override val vendorName: String? = null,
  override val addonName: String? = null,
) : CompileSdkVersion

internal open class SettingsCompileSdkReleaseSpecImpl : CompileSdkReleaseSpec {
  override var sdkExtension: Int? = null
  override var minorApiLevel: Int? = null
}
