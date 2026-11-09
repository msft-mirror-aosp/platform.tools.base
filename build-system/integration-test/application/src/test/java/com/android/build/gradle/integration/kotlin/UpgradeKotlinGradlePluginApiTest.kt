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

package com.android.build.gradle.integration.kotlin

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.testutils.TestUtils.BUILT_IN_KOTLIN_VERSION
import com.android.testutils.TestUtils.LATEST_KOTLIN_VERSION
import com.android.testutils.TestUtils.MINIMUM_BUILT_IN_KOTLIN_VERSION
import org.junit.Rule
import org.junit.Test

/**
 * Tests Gradle rich version resolution for AGP's published Kotlin Gradle plugin dependency (`prefer` [BUILT_IN_KOTLIN_VERSION] + `reject`
 * `(,[MINIMUM_BUILT_IN_KOTLIN_VERSION])`).
 */
class UpgradeKotlinGradlePluginApiTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication {
      files.add(
        "src/main/java/AppFoo.kt",
        // language=kotlin
        """
        package com.foo.application
        class AppFoo
        """
          .trimIndent(),
      )
    }
  }

  /** When the user does not specify a KGP version, AGP resolves its recommended default ([BUILT_IN_KOTLIN_VERSION]). */
  @Test
  fun testDefaultKotlinVersionWhenNotSpecified() {
    val build = rule.build
    build.executor.run(":app:assembleDebug")

    val result = build.executor.run("buildEnvironment")
    val richConstraint = "{prefer $BUILT_IN_KOTLIN_VERSION; reject (,$MINIMUM_BUILT_IN_KOTLIN_VERSION)}"
    ScannerSubject.assertThat(result.stdout)
      .contains("org.jetbrains.kotlin:kotlin-gradle-plugin:$richConstraint -> $BUILT_IN_KOTLIN_VERSION")
    ScannerSubject.assertThat(result.stdout)
      .contains("org.jetbrains.kotlin:kotlin-gradle-plugin-api:$richConstraint -> $BUILT_IN_KOTLIN_VERSION")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugins-bom:$BUILT_IN_KOTLIN_VERSION")
    ScannerSubject.assertThat(result.stdout).doesNotContain("kotlin-gradle-plugin:$MINIMUM_BUILT_IN_KOTLIN_VERSION")
    ScannerSubject.assertThat(result.stdout).doesNotContain("kotlin-gradle-plugin-api:$MINIMUM_BUILT_IN_KOTLIN_VERSION")
    ScannerSubject.assertThat(result.stdout).doesNotContain("kotlin-gradle-plugins-bom:$MINIMUM_BUILT_IN_KOTLIN_VERSION")
    ScannerSubject.assertThat(result.stdout).doesNotContain(LATEST_KOTLIN_VERSION)
  }

  /** Users can stay on the minimum supported KGP version ([MINIMUM_BUILT_IN_KOTLIN_VERSION]) without being upgraded to default. */
  @Test
  fun testExplicitMinimumKotlinVersion() {
    val build = rule.build {
      rootProject { buildscript { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$MINIMUM_BUILT_IN_KOTLIN_VERSION") } }
    }

    build.executor.run(":app:assembleDebug")

    val result = build.executor.run("buildEnvironment")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugin-api:$MINIMUM_BUILT_IN_KOTLIN_VERSION")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugin:$MINIMUM_BUILT_IN_KOTLIN_VERSION")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugins-bom:$MINIMUM_BUILT_IN_KOTLIN_VERSION")
    ScannerSubject.assertThat(result.stdout).doesNotContain("-> $BUILT_IN_KOTLIN_VERSION")
  }

  /** Users can pick an intermediate KGP version between minimum and default without being forced up to [BUILT_IN_KOTLIN_VERSION]. */
  @Test
  fun testExplicitIntermediateKotlinVersion() {
    val intermediateVersion = "2.3.10"
    val build = rule.build { rootProject { buildscript { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$intermediateVersion") } } }

    build.executor.run(":app:assembleDebug")

    val result = build.executor.run("buildEnvironment")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugin-api:$intermediateVersion")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugin:$intermediateVersion")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugins-bom:$intermediateVersion")
    ScannerSubject.assertThat(result.stdout).doesNotContain("-> $BUILT_IN_KOTLIN_VERSION")
  }

  /**
   * Applying another Kotlin plugin (such as `org.jetbrains.kotlin.plugin.compose`) at an intermediate version aligns KGP to that version
   * via `kotlin-gradle-plugins-bom` instead of upgrading KGP to [BUILT_IN_KOTLIN_VERSION].
   */
  @Test
  fun testIndirectIntermediateKotlinVersionViaComposePlugin() {
    val intermediateVersion = "2.3.10"
    val build = rule.build {
      rootProject {
        buildscript {
          classpath("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:$intermediateVersion")
        }
      }
    }

    build.executor.run(":app:assembleDebug")

    val result = build.executor.run("buildEnvironment")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugin-api:$intermediateVersion")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugin:$intermediateVersion")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugins-bom:$intermediateVersion")
    ScannerSubject.assertThat(result.stdout).doesNotContain("-> $BUILT_IN_KOTLIN_VERSION")
  }

  /** Test that users can upgrade the version of kotlin artifacts by adding KGP to their buildscript classpath with the desired version. */
  @Test
  fun testUpgradingKotlinBaseApiPlugin() {
    val build = rule.build { rootProject { buildscript { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$LATEST_KOTLIN_VERSION") } } }

    build.executor.run(":app:assembleDebug")

    val result = build.executor.run("buildEnvironment")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugin-api:$LATEST_KOTLIN_VERSION")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugin:$LATEST_KOTLIN_VERSION")
    ScannerSubject.assertThat(result.stdout).contains("org.jetbrains.kotlin:kotlin-gradle-plugins-bom:$LATEST_KOTLIN_VERSION")
  }

  /** Explicitly requesting a KGP version below [MINIMUM_BUILT_IN_KOTLIN_VERSION] is rejected with AGP's `because` message. */
  @Test
  fun testUnsupportedKotlinVersionBelowMinimumIsRejected() {
    val build = rule.build { rootProject { buildscript { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22") } } }

    val result = build.executor.expectFailure().run(":app:assembleDebug")
    result.assertErrorContains("AGP requires Kotlin Gradle plugin $MINIMUM_BUILT_IN_KOTLIN_VERSION or newer.")
    result.assertErrorContains("Run './gradlew buildEnvironment' to find the plugin that requests an older version")
  }
}
