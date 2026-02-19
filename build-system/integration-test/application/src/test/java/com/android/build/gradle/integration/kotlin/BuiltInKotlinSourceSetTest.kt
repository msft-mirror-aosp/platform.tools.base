/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.options.BooleanOption
import java.io.File
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Tests that built-in Kotlin works correctly with custom source sets. */
@RunWith(Parameterized::class)
class BuiltInKotlinSourceSetTest(private val builtInKotlin: Boolean, private val disallowKotlinSourceSets: Boolean) {

  companion object {

    @Parameterized.Parameters(name = "builtInKotlin={0},disallowKotlinSourceSets={1}")
    @JvmStatic
    fun parameters() =
      listOf(
        // disallowKotlinSourceSets takes effect only when builtInKotlin=true
        arrayOf(false, BooleanOption.DISALLOW_KOTLIN_SOURCE_SETS.defaultValue),
        arrayOf(true, false),
        arrayOf(true, true),
      )
  }

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication { @Suppress("DEPRECATION") if (!builtInKotlin) applyPlugin(PluginType.KOTLIN_ANDROID) }
      gradleProperties {
        add(BooleanOption.BUILT_IN_KOTLIN, builtInKotlin)
        if (!builtInKotlin) add(BooleanOption.USE_NEW_DSL, false)
        add(BooleanOption.DISALLOW_KOTLIN_SOURCE_SETS, disallowKotlinSourceSets)
      }
    }

  @Test
  fun `test source sets are added using android { sourceSets } DSL`() {
    val build =
      rule.build {
        androidApplication {
          pluginCallbacks += AddAndroidSourceSetCallback::class.java
          pluginCallbacks += PrintSourceSetsCallback::class.java
        }
      }

    val result = build.executor.run(":app:help")

    if (builtInKotlin) {
      result.assertOutputContains(
        """
        Contents of Android and Kotlin 'main' source set:
        androidMainSourceSet.java.directories = [src/main/java, src/extraAndroidSourceSet/java]
        androidMainSourceSet.kotlin.directories = [src/main/java, src/main/kotlin, src/extraAndroidSourceSet/kotlin]
        kotlinMainSourceSet.kotlin.srcDirs = null
        """
          .trimIndent()
      )
    } else {
      result.assertOutputContains(
        """
        Contents of Android and Kotlin 'main' source set:
        androidMainSourceSet.java.directories = [src/main/java, src/extraAndroidSourceSet/java]
        androidMainSourceSet.kotlin.directories = [src/main/kotlin, src/main/java, src/extraAndroidSourceSet/java, src/extraAndroidSourceSet/kotlin]
        kotlinMainSourceSet.kotlin.srcDirs = [src/main/kotlin, src/main/java, src/extraAndroidSourceSet/java, src/extraAndroidSourceSet/kotlin]
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `test source sets are added using kotlin { sourceSets } DSL`() {
    val build =
      rule.build {
        androidApplication {
          pluginCallbacks += AddKotlinSourceSetCallback::class.java
          pluginCallbacks += PrintSourceSetsCallback::class.java
        }
      }

    if (builtInKotlin) {
      if (disallowKotlinSourceSets) {
        val result = build.executor.expectFailure().run(":app:help")
        result.assertErrorContains("Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin.")
      } else {
        val result = build.executor.run(":app:help")
        result.assertOutputContains(
          """
          Contents of Android and Kotlin 'main' source set:
          androidMainSourceSet.java.directories = [src/main/java]
          androidMainSourceSet.kotlin.directories = [src/main/java, src/main/kotlin]
          kotlinMainSourceSet.kotlin.srcDirs = [src/main/kotlin, src/extraKotlinSourceSet/kotlin]
          """
            .trimIndent()
        )
      }
    } else {
      val result = build.executor.run(":app:help")
      result.assertOutputContains(
        """
        Contents of Android and Kotlin 'main' source set:
        androidMainSourceSet.java.directories = [src/main/java]
        androidMainSourceSet.kotlin.directories = [src/main/kotlin, src/main/java, src/extraKotlinSourceSet/kotlin]
        kotlinMainSourceSet.kotlin.srcDirs = [src/main/kotlin, src/main/java, src/extraKotlinSourceSet/kotlin]
        """
          .trimIndent()
      )
    }
  }
}

class AddAndroidSourceSetCallback : GenericCallback {

  override fun handleProject(project: Project) {
    val androidExtension = project.extensions.getByType(CommonExtension::class.java)
    val androidMainSourceSet = androidExtension.sourceSets.getByName("main")

    androidMainSourceSet.java.directories += "src/extraAndroidSourceSet/java"
    androidMainSourceSet.kotlin.directories += "src/extraAndroidSourceSet/kotlin"
  }
}

class AddKotlinSourceSetCallback : GenericCallback {

  override fun handleProject(project: Project) {
    val kotlinExtension = project.extensions.getByType(KotlinAndroidProjectExtension::class.java)
    val kotlinMainSourceSet = kotlinExtension.sourceSets.maybeCreate("main")

    kotlinMainSourceSet.kotlin.srcDir("src/extraKotlinSourceSet/kotlin")
  }
}

class PrintSourceSetsCallback : GenericCallback {

  override fun handleProject(project: Project) {
    val androidExtension = project.extensions.getByType(CommonExtension::class.java)
    val androidMainSourceSet = androidExtension.sourceSets.getByName("main")

    val kotlinExtension = project.extensions.getByType(KotlinAndroidProjectExtension::class.java)
    val kotlinMainSourceSet = kotlinExtension.sourceSets.findByName("main")

    fun Collection<File>.toRelativePaths() = map { (if (it.isAbsolute) it.relativeTo(project.projectDir) else it).invariantSeparatorsPath }
    fun Collection<String>.toRelativePaths() = map { File(it) }.toRelativePaths()

    project.afterEvaluate {
      println("Contents of Android and Kotlin 'main' source set:")
      println("androidMainSourceSet.java.directories = ${androidMainSourceSet.java.directories.toRelativePaths()}")
      println("androidMainSourceSet.kotlin.directories = ${androidMainSourceSet.kotlin.directories.toRelativePaths()}")
      println("kotlinMainSourceSet.kotlin.srcDirs = ${kotlinMainSourceSet?.kotlin?.srcDirs?.toRelativePaths()}")
    }
  }
}
