/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.internal.tasks

import com.android.testutils.TestResources
import com.android.testutils.TestUtils
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Test cases for [OptimizeResourcesTask]. */
@RunWith(Parameterized::class)
class OptimizeResourcesTest(private val enableResourceObfuscation: Boolean) {

  private val testApkName = "santa-tracker-release.apk"

  private val aaptOptimizeCommand = "optimize"

  private val testAapt2 = TestUtils.getAapt2().toFile().absoluteFile

  @get:Rule val temporaryFolder = TemporaryFolder()

  companion object {

    @JvmStatic @Parameterized.Parameters fun enableResourceObfuscation() = arrayOf(true, false)
  }

  @Test
  fun testInvokeAaptOptimize_producesExpectedOutput() {
    val sourceApk = TestResources.getFile(OptimizeResourcesTest::class.java, testApkName)
    sourceApk.setExecutable(true)

    val testFolder = temporaryFolder.newFolder()
    val optimizedApk = File(testFolder, "optimized.apk")
    val resourceConfig = File(testFolder, "resources.cfg")

    val flags =
      listOf(
        aaptOptimizeCommand,
        sourceApk.path,
        AAPT2OptimizeFlags.ENABLE_SPARSE_ENCODING.flag,
        AAPT2OptimizeFlags.SHORTEN_RESOURCE_PATHS.flag,
        "${AAPT2OptimizeFlags.RESOURCE_PATH_SHORTENING_MAP.flag}=${resourceConfig.absolutePath}",
        AAPT2OptimizeFlags.COLLAPSE_RESOURCE_NAMES.flag,
        "-o",
        optimizedApk.path,
      )

    if (enableResourceObfuscation) {
      invokeAapt(testAapt2, *flags.toTypedArray())
    } else {
      invokeAapt(testAapt2, *flags.minus(AAPT2OptimizeFlags.COLLAPSE_RESOURCE_NAMES.flag).toTypedArray())
    }

    val previousApkSize = sourceApk.length()
    val optimizedApkSize = optimizedApk.length()
    assertThat(previousApkSize).isNotEqualTo(0)
    assertThat(optimizedApkSize).isAtMost(previousApkSize)
    assertThat(optimizedApkSize).isNotEqualTo(0)

    assertThat(resourceConfig.readText())
      .contains(
        """
        res/anim-v21/design_bottom_sheet_slide_in.xml -> res/-Q.xml
        res/anim-v21/design_bottom_sheet_slide_out.xml -> res/20.xml
        res/anim/abc_fade_in.xml -> res/y4.xml
        res/anim/abc_fade_out.xml -> res/Bd.xml
        res/anim/abc_grow_fade_in_from_bottom.xml -> res/aM1.xml
        res/anim/abc_popup_enter.xml -> res/k_.xml
        res/anim/abc_popup_exit.xml -> res/UX.xml
        res/anim/abc_shrink_fade_out_from_bottom.xml -> res/5T.xml
        """
          .trimIndent()
      )
  }
}
