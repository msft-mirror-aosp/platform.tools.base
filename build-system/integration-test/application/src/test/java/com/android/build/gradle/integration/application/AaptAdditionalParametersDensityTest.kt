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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.AabSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.junit.Rule
import org.junit.Test

/**
 * Tests targeting a single screen density with `androidResources.additionalParameters`.
 *
 * `resourceConfigurations` (`resConfigs`) is deprecated, and the documented replacement for the screen density half of it is to pass
 * aapt2's `--preferred-density` flag directly. That makes this an API contract rather than an incidental passthrough, so the behaviour is
 * pinned here.
 */
class AaptAdditionalParametersDensityTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication {
      android { defaultConfig.versionCode = 1 }
      files {
        // A resource that exists at every density.
        add("src/main/res/raw-mdpi/everywhere", "mdpi")
        add("src/main/res/raw-hdpi/everywhere", "hdpi")
        add("src/main/res/raw-xhdpi/everywhere", "xhdpi")
        add("src/main/res/raw-xxhdpi/everywhere", "xxhdpi")
        // A resource that only exists above the density we target, to exercise aapt2's closest-match fallback.
        add("src/main/res/raw-xxhdpi/only_high", "xxhdpi")
      }
    }
  }

  @Test
  fun preferredDensityKeepsOnlyTheTargetDensity() {
    val build = rule.build {
      androidApplication { android.androidResources.additionalParameters += listOf("--preferred-density", "hdpi") }
    }

    build.executor.run("clean", "assembleDebug")

    build.androidApplication().assertApk(ApkSelector.DEBUG) {
      androidResources { containsExactly("raw-hdpi-v4/everywhere", "raw-xxhdpi-v4/only_high") }
    }
  }

  /**
   * The behaviour that distinguishes `--preferred-density` from a plain configuration filter: a resource with no version at the target
   * density survives, using the closest available density, rather than being dropped.
   */
  @Test
  fun preferredDensityFallsBackToTheClosestAvailableDensity() {
    val build = rule.build {
      androidApplication { android.androidResources.additionalParameters += listOf("--preferred-density", "hdpi") }
    }

    build.executor.run("clean", "assembleDebug")

    build.androidApplication().assertApk(ApkSelector.DEBUG) { androidResources { contains("raw-xxhdpi-v4/only_high") } }
  }

  /**
   * Migrating from `resConfigs "hdpi"` to `--preferred-density hdpi` must not change the packaged resources, since the former is
   * implemented in terms of the latter.
   */
  @Test
  fun additionalParametersMatchesResConfigs() {
    val viaResConfigs = rule.build {
      androidApplication { @Suppress("DEPRECATION") android.defaultConfig.resourceConfigurations += "hdpi" }
    }

    viaResConfigs.executor.run("clean", "assembleDebug")

    viaResConfigs.androidApplication().assertApk(ApkSelector.DEBUG) {
      androidResources { containsExactly("raw-hdpi-v4/everywhere", "raw-xxhdpi-v4/only_high") }
    }
  }

  @Test
  fun allDensitiesAreKeptByDefault() {
    val build = rule.build

    build.executor.run("clean", "assembleDebug")

    build.androidApplication().assertApk(ApkSelector.DEBUG) {
      androidResources {
        containsExactly(
          "raw-mdpi-v4/everywhere",
          "raw-hdpi-v4/everywhere",
          "raw-xhdpi-v4/everywhere",
          "raw-xxhdpi-v4/everywhere",
          "raw-xxhdpi-v4/only_high",
        )
      }
    }
  }

  /**
   * `additionalParameters` is forwarded to the bundle's resource link as well as the APK's, so targeting a density strips densities from
   * the Android App Bundle too. That is almost never intended, since a bundle is expected to carry every density so that density-specific
   * APKs can be generated from it. This test pins the behaviour so the caveat we document stays accurate.
   */
  @Test
  fun preferredDensityAlsoAffectsTheBundle() {
    val build = rule.build {
      androidApplication { android.androidResources.additionalParameters += listOf("--preferred-density", "hdpi") }
    }

    build.executor.run("clean", "bundleDebug")

    build.androidApplication().assertAab(AabSelector.DEBUG) {
      contains("/base/res/raw-hdpi-v4/everywhere")
      doesNotContain("/base/res/raw-mdpi-v4/everywhere")
      doesNotContain("/base/res/raw-xhdpi-v4/everywhere")
      doesNotContain("/base/res/raw-xxhdpi-v4/everywhere")
    }
  }
}
