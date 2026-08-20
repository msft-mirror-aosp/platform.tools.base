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

package com.android.build.gradle.integration.kotlin

import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import java.io.File
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class LegacyKotlinDslMergedFlavorTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication { pluginCallbacks += MergedFlavorCallback::class.java }
    gradleProperties { add(com.android.build.gradle.options.BooleanOption.USE_NEW_DSL, false) }
  }

  @Test
  fun `mergedFlavor source compatibility`() {
    val build = rule.build
    // Running help will trigger configuration and execute the callback.
    val result = build.executor.run(":app:help")

    // Assert that the callback was actually executed
    result.stdout.use { scanner -> ScannerSubject.assertThat(scanner).contains("MergedFlavorCallback executed") }
  }

  class MergedFlavorCallback : LegacyApplicationCallback {
    override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
      extension.applicationVariants.all { variant: ApplicationVariant ->
        // Mark as executed
        println("MergedFlavorCallback executed")

        val fileF = File(project.projectDir, "f")
        val fileG = File(project.projectDir, "g")
        val fileH = File(project.projectDir, "h")

        variant.mergedFlavor.manifestPlaceholders += mapOf("a" to "b")
        variant.mergedFlavor.testInstrumentationRunnerArguments += mapOf("c" to "d")
        variant.mergedFlavor.resourceConfigurations += "e"
        variant.mergedFlavor.proguardFiles += fileF
        variant.mergedFlavor.consumerProguardFiles += fileG
        variant.mergedFlavor.testProguardFiles += fileH

        // Verify immediately during configuration
        assert(variant.mergedFlavor.manifestPlaceholders["a"] == "b")
        assert(variant.mergedFlavor.testInstrumentationRunnerArguments["c"] == "d")
        assert(variant.mergedFlavor.resourceConfigurations.contains("e"))
        assert(variant.mergedFlavor.proguardFiles.any { it.name == "f" })
        assert(variant.mergedFlavor.consumerProguardFiles.any { it.name == "g" })
        assert(variant.mergedFlavor.testProguardFiles.any { it.name == "h" })
      }
    }
  }
}
