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

import com.android.Version
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.testutils.TestUtils.BUILT_IN_KOTLIN_VERSION
import com.android.testutils.TestUtils.KOTLIN_VERSION_FOR_TESTS
import com.android.testutils.TestUtils.MINIMUM_BUILT_IN_KOTLIN_VERSION
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.Rule
import org.junit.Test

/** Tests for `android.kotlinGradlePluginVersion` in the settings file. */
class SettingsKotlinGradlePluginVersionTest {

  @get:Rule
  val rule = GradleRule.from {
    buildFileType = BuildFileType.KTS
    settings {
      applyPlugin(PluginType.ANDROID_SETTINGS)
      android {
        compileSdk = DEFAULT_COMPILE_SDK_VERSION
        minSdk = 24
      }
    }
    androidApplication(createMinimumProject = false) {
      android { namespace = "com.foo.application" }
      files.setupMinimumManifest()
      files.add("src/main/java/com/foo/application/AppFoo.kt", "package com.foo.application\nclass AppFoo\n")
    }
  }

  @Test
  fun versionIsUsedByAllProjects() {
    val build = rule.build {
      settings { android { kotlinGradlePluginVersion = "2.3.10" } }
      // An older version than the one in settings, whereas the default version of AGP is newer.
      rootProject { buildscript { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22") } }
      // A Kotlin Gradle plugin without a version, which is not on the classpath of the root project.
      genericProject(":lib") { applyPlugin(PluginType.Custom("org.jetbrains.kotlin.plugin.compose")) }
    }
    // KGP < 2.3.20 calls Configuration.setVisible(boolean), which is deprecated in Gradle 9.8.2.
    val executor = build.executor.withFailOnWarning(false)

    executor.run(":app:assembleDebug").assertOutputDoesNotContain("android.kotlinGradlePluginVersion")
    executor
      .run(":buildEnvironment")
      .assertOutputContains(
        """
        +--- org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22 -> 2.3.10
        org.jetbrains.kotlin:kotlin-gradle-plugin:$AGP_KOTLIN_VERSION_CONSTRAINT -> 2.3.10
        +--- org.jetbrains.kotlin:kotlin-gradle-plugins-bom:{strictly 2.3.10} -> 2.3.10
        """
          .trimIndent()
      )
    executor
      .run(":lib:buildEnvironment")
      .assertOutputContains(
        """
        +--- org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.3.10
        |    \--- org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.10
        """
          .trimIndent()
      )
  }

  @Test
  fun versionNewerThanDefaultOfAgpIsUsed() {
    val build = rule.build { settings { android { kotlinGradlePluginVersion = KOTLIN_VERSION_FOR_TESTS } } }

    build.executor.run(":app:assembleDebug")
    build.executor.run(":app:assembleDebug").assertOutputContains("Reusing configuration cache.")
    build.executor
      .run(":app:dependencies", "--configuration", "debugCompileClasspath")
      .assertOutputContains("--- org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION_FOR_TESTS")
  }

  @Test
  fun requestedVersionsAreOverriddenWithWarnings() {
    val build = rule.build {
      settings { android { kotlinGradlePluginVersion = "2.3.10" } }
      rootProject { buildscript { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin-api:$BUILT_IN_KOTLIN_VERSION") } }
      androidApplication {
        applyPlugin(PluginType.Custom("org.jetbrains.kotlin.plugin.compose"), version = BUILT_IN_KOTLIN_VERSION)
      }
    }

    build.executor
      .withFailOnWarning(false)
      .run(":buildEnvironment")
      .assertOutputContains(
        """
        Plugin 'org.jetbrains.kotlin.plugin.compose' version '$BUILT_IN_KOTLIN_VERSION' was overridden to '2.3.10' by android.kotlinGradlePluginVersion in settings.
        Dependency 'org.jetbrains.kotlin:kotlin-gradle-plugin-api:$BUILT_IN_KOTLIN_VERSION' of root project 'project' was overridden to '2.3.10' by android.kotlinGradlePluginVersion in settings.
        +--- org.jetbrains.kotlin:kotlin-gradle-plugin-api:$BUILT_IN_KOTLIN_VERSION -> 2.3.10
        +--- org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.3.10
        |    \--- org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.10
        +--- org.jetbrains.kotlin:kotlin-gradle-plugins-bom:{strictly 2.3.10} -> 2.3.10
        """
          .trimIndent()
      )
  }

  @Test
  fun versionOfBuildSrcIsUsedWithWarning() {
    val build = rule.build { settings { android { kotlinGradlePluginVersion = "2.3.10" } } }
    // Get AGP and its default version of KGP from buildSrc instead, which is a separate build with its own settings.
    val settingsFile = build.directory.resolve("settings.gradle.kts").readText()
    val repositories = Regex("""dependencyResolutionManagement \{[\s\S]*?\n}""").find(settingsFile)!!.value
    val buildSrc = build.directory.resolve("buildSrc").createDirectories()
    buildSrc.resolve("settings.gradle.kts").writeText(repositories)
    buildSrc
      .resolve("build.gradle.kts")
      .writeText(
        """
        plugins { `java-library` }
        dependencies { implementation("com.android.tools.build:gradle:${Version.ANDROID_GRADLE_PLUGIN_VERSION}") }
        """
          .trimIndent()
      )
    build.directory.resolve("build.gradle.kts").writeText("")

    build.executor
      .run(":app:help")
      .assertOutputContains(
        """
        WARNING: Kotlin Gradle plugin $BUILT_IN_KOTLIN_VERSION is used instead of 2.3.10 set by android.kotlinGradlePluginVersion in settings, because it is already on the build classpath (for example as a dependency of buildSrc).
        Add android.sync.suppressAgpWarnings=GENERIC to the gradle.properties file to suppress this warning.
        """
          .trimIndent()
      )
  }

  @Test
  fun unsetVersionChangesNothing() {
    val result = rule.build.executor.run(":buildEnvironment")
    result.assertOutputContains("org.jetbrains.kotlin:kotlin-gradle-plugin:$AGP_KOTLIN_VERSION_CONSTRAINT -> $BUILT_IN_KOTLIN_VERSION")
    result.assertOutputDoesNotContain("kotlin-gradle-plugins-bom:{strictly")
  }

  companion object {
    private const val AGP_KOTLIN_VERSION_CONSTRAINT = "{prefer $BUILT_IN_KOTLIN_VERSION; reject (,$MINIMUM_BUILT_IN_KOTLIN_VERSION)}"
  }
}
