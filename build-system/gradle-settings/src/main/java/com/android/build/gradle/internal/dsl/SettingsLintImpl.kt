/*
 * Copyright (C) 2026 The Android Open Source Project
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

import com.android.build.api.dsl.Lint
import com.android.build.api.dsl.TargetSdkSpec
import com.android.build.api.dsl.TargetSdkVersion
import java.io.File
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory

internal open class SettingsLintImpl @Inject constructor(private val objectFactory: ObjectFactory) : Lint {

  override var abortOnError: Boolean = true
  override var absolutePaths: Boolean = true
  override var explainIssues: Boolean = true
  override var checkReleaseBuilds: Boolean = true
  override var htmlReport: Boolean = true
  override var xmlReport: Boolean = true
  override var checkDependencies: Boolean = false

  protected var _baselinePath: String? = null
  override var baseline: File?
    get() {
      return _baselinePath?.let { File(it) }
    }
    set(value) {
      _baselinePath = value?.path
    }

  protected var _lintConfigPath: String? = null
  override var lintConfig: File?
    get() = _lintConfigPath?.let { File(it) }
    set(value) {
      _lintConfigPath = value?.path
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

  private fun createTargetSdkSpec(): SettingsTargetSdkSpecImpl {
    return objectFactory.newInstance(SettingsTargetSdkSpecImpl::class.java).also { it.version = _targetSdk }
  }

  /** This function makes calling sdk block without doing `version = xx` has not effects */
  private fun <T> updateIfChanged(oldValue: T?, newValue: T?, setter: (T?) -> Unit) {
    if (oldValue != newValue) {
      setter(newValue)
    }
  }

  // Error for unsupported options. These are temporary, as these properties will be removed when
  // we move to aggregated reporting.
  override var textOutput: File?
    get() = null
    set(value) {
      throw UnsupportedOperationException("The lint 'textOutput' property is not supported in the settings plugin.")
    }

  override var htmlOutput: File?
    get() = null
    set(value) {
      throw UnsupportedOperationException("The lint 'htmlOutput' property is not supported in the settings plugin.")
    }

  override var xmlOutput: File?
    get() = null
    set(value) {
      throw UnsupportedOperationException("The lint 'xmlOutput' property is not supported in the settings plugin.")
    }

  override var sarifOutput: File?
    get() = null
    set(value) {
      throw UnsupportedOperationException("The lint 'sarifOutput' property is not supported in the settings plugin.")
    }

  override val disable: MutableSet<String> = mutableSetOf()
  override val enable: MutableSet<String> = mutableSetOf()
  override val checkOnly: MutableSet<String> = mutableSetOf()
  override var noLines: Boolean = false
  override var quiet: Boolean = false
  override var checkAllWarnings: Boolean = false
  override var ignoreWarnings: Boolean = false
  override var warningsAsErrors: Boolean = false
  override var checkTestSources: Boolean = false
    set(value) {
      field = value
      if (value) {
        ignoreTestSources = false
      }
    }

  override var ignoreTestSources: Boolean = false
    set(value) {
      field = value
      if (value) {
        checkTestSources = false
      }
    }

  override var ignoreTestFixturesSources: Boolean = false
  override var checkGeneratedSources: Boolean = false
  override var showAll: Boolean = false
  override var textReport: Boolean = false
  override var sarifReport: Boolean = false
  override val informational: MutableSet<String> = mutableSetOf()
  @Deprecated("Ignore and disable are synonyms", replaceWith = ReplaceWith("disable"))
  override val ignore: MutableSet<String>
    get() = disable

  override val warning: MutableSet<String> = mutableSetOf()
  override val error: MutableSet<String> = mutableSetOf()
  override val fatal: MutableSet<String> = mutableSetOf()
}
