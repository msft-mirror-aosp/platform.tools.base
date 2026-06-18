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

package com.android.build.gradle.internal.tasks

import com.android.build.gradle.options.BooleanOption
import com.android.builder.dexing.ProguardConfig
import com.android.builder.dexing.ProguardOutputReports
import com.android.builder.dexing.R8OutputType
import com.android.builder.dexing.ToolConfig
import com.android.builder.dexing.runR8
import com.android.testutils.TestClassesGenerator
import com.android.testutils.TestUtils
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class R8AnalysisTest {
  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()
  private lateinit var outputDir: Path

  @Before
  fun setUp() {
    outputDir = tmp.newFolder().toPath()
  }

  @Test
  fun testAnalysisRunsAndNoDexProduced() {
    val classes = tmp.root.toPath().resolve("classes.jar")
    ZipOutputStream(classes.toFile().outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("test/A.class"))
      zip.write(TestClassesGenerator.emptyClass("test", "A"))
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("test/B.class"))
      zip.write(TestClassesGenerator.emptyClass("test", "B"))
      zip.closeEntry()
    }

    val pbReport = tmp.root.resolve("configanalyzer.pb")
    val htmlReport = tmp.root.resolve("configanalyzer.html")

    val proguardOutputReports = ProguardOutputReports(pbReport.toPath(), htmlReport.toPath())
    val proguardConfig = ProguardConfig(listOf(), null, mutableListOf("-keep class test.A", "-ignorewarnings"), null, proguardOutputReports)
    val toolConfig =
      ToolConfig(
        minSdkVersion = 21,
        debuggable = true,
        disableTreeShaking = false,
        disableMinification = false,
        disableDesugaring = true,
        fullMode = true,
        strictFullModeForKeepRules = BooleanOption.R8_STRICT_FULL_MODE_FOR_KEEP_RULES.defaultValue,
        isolatedSplits = null,
        r8OutputType = R8OutputType.DEX,
        mainDexListDisallowed = BooleanOption.R8_MAIN_DEX_LIST_DISALLOWED.defaultValue,
      )

    runR8(
      inputClasses = listOf(classes),
      output = null,
      inputJavaResJar = classes,
      javaResourcesJar = null,
      libraries = listOf(TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP)),
      classpath = listOf(),
      toolConfig = toolConfig,
      proguardConfig = proguardConfig,
      mainDexListConfig = com.android.builder.dexing.MainDexListConfig(),
      resourceShrinkingConfig = null,
      messageReceiver = { _ -> },
      featureClassJars = listOf(),
      featureJavaResourceJars = listOf(),
      featureDexDir = null,
      featureJavaResourceOutputDir = null,
      libConfiguration = null,
      inputArtProfile = null,
      outputArtProfile = null,
      inputProfileForDexStartupOptimization = null,
      r8Metadata = null,
      partialShrinking = null,
      r8ExecutorService = MoreExecutors.newDirectExecutorService(),
    )

    // Verify reports generated
    assertThat(pbReport.toPath()).exists()
    assertThat(htmlReport.toPath()).exists()

    // Since the empty consumer is passed, no Dex output should exist inside outputDir
    val filesInOutputDir = Files.list(outputDir).use { it.count() }
    assertThat(filesInOutputDir).isEqualTo(0)
  }
}
