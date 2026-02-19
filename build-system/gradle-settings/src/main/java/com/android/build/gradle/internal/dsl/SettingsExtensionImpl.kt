/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.build.api.dsl.CompileSdkSpec
import com.android.build.api.dsl.CompileSdkVersion
import com.android.build.api.dsl.Execution
import com.android.build.api.dsl.MinSdkSpec
import com.android.build.api.dsl.MinSdkVersion
import com.android.build.api.dsl.SettingsExtension
import com.android.build.api.dsl.TargetSdkSpec
import com.android.build.api.dsl.TargetSdkVersion
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory

internal open class SettingsExtensionImpl @Inject constructor(private val objectFactory: ObjectFactory) : SettingsExtension {

  protected var _compileSdk: CompileSdkVersion? = null

  override var compileSdk: Int?
    get() {
      return if (_compileSdk?.addonName != null || _compileSdk?.vendorName != null || _compileSdk?.codeName != null) {
        return null
      } else {
        _compileSdk?.apiLevel
      }
    }
    set(value) {
      compileSdk { version = value?.let { release(it) } }
    }

  override var compileSdkExtension: Int?
    get() = _compileSdk?.sdkExtension
    set(value) {
      compileSdk {
        _compileSdk?.apiLevel?.let { apiLevel ->
          version =
            release(apiLevel) {
              sdkExtension = value
              minorApiLevel = _compileSdk?.minorApiLevel
            }
        }
      }
    }

  override var compileSdkPreview: String?
    get() = _compileSdk?.codeName
    set(value) {
      compileSdk { version = value?.let { preview(value) } }
    }

  override fun compileSdkAddon(vendor: String, name: String, version: Int) {
    compileSdk { this.version = addon(vendor = vendor, name = name, version) }
  }

  override fun compileSdk(action: CompileSdkSpec.() -> Unit) {
    createCompileSdkSpec().also {
      action.invoke(it)
      updateIfChanged(_compileSdk, it.version) { _compileSdk = it }
    }
  }

  open fun compileSdk(action: Action<CompileSdkSpec>) {
    createCompileSdkSpec().also {
      action.execute(it)
      updateIfChanged(_compileSdk, it.version) { _compileSdk = it }
    }
  }

  // TODO(b/421964815): remove the support for groovy space assignment(e.g `compileSdk 24`).
  @Deprecated("To be removed after Gradle drops space assignment support", ReplaceWith("compileSdk { version = release(value) }"))
  open fun compileSdk(value: Int) {
    compileSdk { version = release(value) }
  }

  override val addOnVendor: String?
    get() = _compileSdk?.vendorName

  override val addOnName: String?
    get() = _compileSdk?.addonName

  override val addOnVersion: Int?
    get() {
      return if (_compileSdk?.addonName != null || _compileSdk?.vendorName != null) {
        _compileSdk?.apiLevel
      } else null
    }

  protected var _minSdk: MinSdkVersion? = null

  override var minSdk: Int?
    get() = _minSdk?.apiLevel
    set(value) {
      minSdk { version = value?.let { release(value) } }
    }

  override var minSdkPreview: String?
    get() = _minSdk?.codeName
    set(value) {
      minSdk { version = value?.let { preview(value) } }
    }

  override fun minSdk(action: MinSdkSpec.() -> Unit) {
    createMinSdkSpec().also {
      action.invoke(it)
      updateIfChanged(_minSdk, it.version) { _minSdk = it }
    }
  }

  open fun minSdk(action: Action<MinSdkSpec>) {
    createMinSdkSpec().also {
      action.execute(it)
      updateIfChanged(_minSdk, it.version) { _minSdk = it }
    }
  }

  // TODO(b/421964815): remove the support for groovy space assignment(e.g `minSdk 24`).
  @Deprecated("To be removed after Gradle drops space assignment support", ReplaceWith("minSdk { version = release(value) }"))
  open fun minSdk(value: Int) {
    minSdk { version = release(value) }
  }

  protected var _targetSdk: TargetSdkVersion? = null

  override var targetSdk: Int?
    get() = _targetSdk?.apiLevel
    set(value) {
      targetSdk { version = value?.let { release(value) } }
    }

  override var targetSdkPreview: String?
    get() = _targetSdk?.codeName
    set(value) {
      targetSdk { version = value?.let { preview(value) } }
    }

  override fun targetSdk(action: TargetSdkSpec.() -> Unit) {
    createTargetSdkSpec().also {
      action.invoke(it)
      updateIfChanged(_targetSdk, it.version) { _targetSdk = it }
    }
  }

  open fun targetSdk(action: Action<TargetSdkSpec>) {
    createTargetSdkSpec().also {
      action.execute(it)
      updateIfChanged(_targetSdk, it.version) { _targetSdk = it }
    }
  }

  // TODO(b/421964815): remove the support for groovy space assignment(e.g `targetSdk 24`).
  @Deprecated("To be removed after Gradle drops space assignment support", ReplaceWith("targetSdk { version = release(value) }"))
  open fun targetSdk(value: Int) {
    targetSdk { version = release(value) }
  }

  override val execution: Execution = objectFactory.newInstance(ExecutionImpl::class.java, objectFactory)

  fun execution(action: Action<Execution>) {
    action.execute(execution)
  }

  override fun execution(action: Execution.() -> Unit) {
    action.invoke(execution)
  }

  override var ndkVersion: String = SdkConstants.NDK_VERSION
  override var ndkPath: String? = null
  override var buildToolsVersion: String = SdkConstants.BUILD_TOOLS_VERSION

  private fun createMinSdkSpec(): SettingsMinSdkSpecImpl {
    return objectFactory.newInstance(SettingsMinSdkSpecImpl::class.java).also { it.version = _minSdk }
  }

  private fun createTargetSdkSpec(): SettingsTargetSdkSpecImpl {
    return objectFactory.newInstance(SettingsTargetSdkSpecImpl::class.java).also { it.version = _targetSdk }
  }

  private fun createCompileSdkSpec(): SettingsCompileSdkSpecImpl {
    return objectFactory.newInstance(SettingsCompileSdkSpecImpl::class.java).also { it.version = _compileSdk }
  }

  /** This function makes calling sdk block without doing `version = xx` has not effects */
  fun <T> updateIfChanged(oldValue: T?, newValue: T?, setter: (T?) -> Unit) {
    if (oldValue != newValue) {
      setter(newValue)
    }
  }
}
