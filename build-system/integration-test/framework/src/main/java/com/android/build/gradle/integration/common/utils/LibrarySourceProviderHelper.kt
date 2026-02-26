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

package com.android.build.gradle.integration.common.utils

import com.android.builder.model.v2.ide.SourceProvider
import java.io.File

class LibrarySourceProviderHelper(projectName: String, projectDir: File, configName: String, private val sourceProvider: SourceProvider?) :
  SourceProviderHelper(projectName, projectDir, configName, sourceProvider) {

  override fun setJavaDir(javaDir: String): LibrarySourceProviderHelper {
    super.setJavaDir(javaDir)
    return this
  }

  override fun setKotlinDirs(vararg kotlinDirs: String): LibrarySourceProviderHelper {
    super.setKotlinDirs(*kotlinDirs)
    return this
  }

  override fun setResourcesDir(resourcesDir: String): LibrarySourceProviderHelper {
    super.setResourcesDir(resourcesDir)
    return this
  }

  override fun setManifestFile(manifestFile: String): LibrarySourceProviderHelper {
    super.setManifestFile(manifestFile)
    return this
  }

  override fun setResDir(resDir: String): LibrarySourceProviderHelper {
    super.setResDir(resDir)
    return this
  }

  override fun setAssetsDir(assetsDir: String): LibrarySourceProviderHelper {
    super.setAssetsDir(assetsDir)
    return this
  }

  override fun setAidlDir(aidlDir: String): LibrarySourceProviderHelper {
    super.setAidlDir(aidlDir)
    return this
  }

  override fun setRenderscriptDir(renderscriptDir: String): LibrarySourceProviderHelper {
    super.setRenderscriptDir(renderscriptDir)
    return this
  }

  override fun setBaselineProfileDir(baselineProfileDir: String): LibrarySourceProviderHelper {
    super.setBaselineProfileDir(baselineProfileDir)
    return this
  }

  override fun setKeepRulesDir(baseKeepRulesDir: String): LibrarySourceProviderHelper {
    super.setKeepRulesDir(baseKeepRulesDir)
    return this
  }

  override fun setJniDir(jniDir: String): LibrarySourceProviderHelper {
    super.setJniDir(jniDir)
    return this
  }

  private lateinit var aarKeepRulesDir: String

  init {
    setAarKeepRulesDir("src/" + configName + "/aarKeepRules")
  }

  fun setAarKeepRulesDir(baseKeepRulesDir: String): SourceProviderHelper {
    this.aarKeepRulesDir = baseKeepRulesDir
    return this
  }

  override fun testV2() {
    super.testV2()
    testSinglePathCollection("aarKeepRules", aarKeepRulesDir, sourceProvider!!.aarKeepRulesDirectories)
  }
}
