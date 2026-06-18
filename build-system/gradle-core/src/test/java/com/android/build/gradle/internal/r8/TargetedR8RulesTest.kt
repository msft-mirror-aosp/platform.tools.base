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

package com.android.build.gradle.internal.r8

import com.android.build.gradle.internal.r8.TargetedR8RulesReadWriter.createJarContents
import com.android.build.gradle.internal.r8.TargetedR8RulesReadWriter.readFromJar
import com.android.testutils.ZipContents
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Unit test for [TargetedR8Rules]. */
@RunWith(Parameterized::class)
class TargetedR8RulesTest(val filterOutGlobalRules: Boolean) {
  companion object {
    @JvmStatic @Parameterized.Parameters(name = "globalOptionsDisallowed={0}") fun globalOptionsDisallowed() = listOf(true, false)
  }

  @get:Rule val tmpDir = TemporaryFolder()

  private fun exampleShrinkRules(): Map<String, String> {
    return mapOf(
        "META-INF/com.android.tools/r8/r8.ext" to "# R8 rules",
        "META-INF/com.android.tools/r8-from-8.2.0/r8-from-8.2.0.ext" to "# R8-from-8.2.0 rules",
        "META-INF/com.android.tools/r8-from-8.0.0-upto-8.2.0/r8-from-8.0.0-upto-8.2.0.ext" to "# R8-from-8.0.0-upto-8.2.0 rules",
        "META-INF/com.android.tools/r8-upto-8.0.0/r8-upto-8.0.0.ext" to "# R8-upto-8.0.0 rules",
        "META-INF/proguard/proguard.pro" to "# Legacy Proguard rules",
      )
      .mapValues {
        // add -dontoptimize which should be filtered out when filterOutGlobalRules = true
        it.value + System.lineSeparator() + "-dontoptimize" + System.lineSeparator()
      }
  }

  @Test
  fun `test producing and consuming targeted R8 rules`() {
    val r8RulesContentsAtProducer: Map<String, String> = exampleShrinkRules()
    val jarFile = tmpDir.root.resolve("lib.jar")
    ZipContents(r8RulesContentsAtProducer.mapValues { it.value.toByteArray() }).writeToFile(jarFile)

    val r8RulesAtConsumer: TargetedR8Rules =
      readFromJar(jarFile, isClassesJarInAar = false, shouldRemoveBannedGlobals = filterOutGlobalRules)
    val r8RulesContentsAtConsumer: Map<String, String> = r8RulesAtConsumer.createJarContents().mapValues { it.value.decodeToString() }

    val expectedRules =
      if (filterOutGlobalRules) {
        r8RulesContentsAtProducer.mapValues { it.value.replace("-dontoptimize", "# REMOVED CONSUMER RULE: -dontoptimize") }
      } else {
        r8RulesContentsAtProducer
      }
    assertEquals(expectedRules, r8RulesContentsAtConsumer)
  }

  @Test
  fun `test Zip-Slip malicious paths are rejected`() {
    val jarFile = tmpDir.root.resolve("malicious.jar")
    java.util.zip.ZipOutputStream(java.io.FileOutputStream(jarFile)).use { zos ->
      // Simulate a Zip-Slip payload targeting Windows using backslashes
      zos.putNextEntry(java.util.zip.ZipEntry("META-INF/com.android.tools/r8/..\\..\\evil.ext"))
      zos.write("malicious payload".toByteArray())
      zos.closeEntry()

      // Simulate a legacy ProGuard payload
      zos.putNextEntry(java.util.zip.ZipEntry("META-INF/proguard/..\\evil.pro"))
      zos.write("malicious payload".toByteArray())
      zos.closeEntry()
    }

    val r8RulesAtConsumer = readFromJar(jarFile, isClassesJarInAar = false, shouldRemoveBannedGlobals = filterOutGlobalRules)

    // Assert that the maliciously named entries were rejected and skipped
    kotlin.test.assertTrue(r8RulesAtConsumer.r8Rules.isEmpty(), "Malicious R8 rule should be rejected")
    kotlin.test.assertTrue(r8RulesAtConsumer.legacyProguardRules.isEmpty(), "Malicious legacy Proguard rule should be rejected")
  }
}
