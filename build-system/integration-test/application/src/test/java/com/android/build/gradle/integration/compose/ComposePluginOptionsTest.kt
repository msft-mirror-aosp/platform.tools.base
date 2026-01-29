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

package com.android.build.gradle.integration.compose

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import org.gradle.api.Project
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.Rule
import org.junit.Test

/** Tests Compose plugin options for KotlinCompile. */
class ComposePluginOptionsTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        applyPlugin(PluginType.COMPOSE_COMPILER_PLUGIN)
        android {
          defaultConfig { minSdk = 24 }
          buildFeatures { compose = true }
        }
        kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
        dependencies { implementation("androidx.compose.runtime:runtime:+") }
        files.add(
          "src/main/java/com/example/KotlinClass.kt",
          // language=kotlin
          """
          class KotlinClass
          """
            .trimIndent(),
        )
        pluginCallbacks += PrintKotlinCompileInfoCallback::class.java
      }
    }

  class PrintKotlinCompileInfoCallback : GenericCallback {
    override fun handleProject(project: Project) {
      project.afterEvaluate {
        project.tasks.withType(KotlinCompile::class.java) { kotlinCompile ->
          kotlinCompile.doLast { task ->
            task as KotlinCompile
            val freeCompilerArgs = task.compilerOptions.freeCompilerArgs.get().joinToString()
            println("freeCompilerArgs: $freeCompilerArgs")
            val pluginOptions =
              task.pluginOptions.get().joinToString {
                it.getAsTaskInputArgs().map { input -> "${input.key}=${input.value}" }.joinToString()
              }
            println("pluginOptions: $pluginOptions")
          }
        }
      }
    }
  }

  /** Regression test for b/318384658. */
  @Test
  fun `test build fails when the user sets sourceInformation`() {
    val build =
      rule.build {
        androidApplication {
          kotlin {
            compilerOptions { freeCompilerArgs.addAll("-P", "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=false") }
          }
        }
      }

    val result = build.executor.expectFailure().run(":app:compileDebugKotlin")

    // The build should fail
    // (see https://youtrack.jetbrains.com/issue/KT-74415#focus=Comments-27-11464464.0-0)
    result.assertErrorContains(
      "Multiple values are not allowed for plugin option androidx.compose.compiler.plugins.kotlin:sourceInformation"
    )
  }

  /** Regression test for b/362780328. */
  @Test
  fun `test include source information by default`() {
    val debugResult =
      rule.build.executor.withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON).run(":app:compileDebugKotlin")
    ScannerSubject.assertThat(debugResult.stdout).contains("androidx.compose.compiler.plugins.kotlin.sourceInformation=true")

    val releaseResult =
      rule.build.executor.withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON).run(":app:compileReleaseKotlin")
    ScannerSubject.assertThat(releaseResult.stdout).contains("androidx.compose.compiler.plugins.kotlin.sourceInformation=true")
  }

  /** Regression test for b/362780328. */
  @Test
  fun `test exclude source information via DSL`() {
    val build = rule.build { androidApplication { pluginCallbacks += CompilerOptionsCallback::class.java } }
    val debugResult = build.executor.withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON).run(":app:compileDebugKotlin")
    ScannerSubject.assertThat(debugResult.stdout).contains("androidx.compose.compiler.plugins.kotlin.sourceInformation=false")

    val releaseResult = build.executor.withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON).run(":app:compileReleaseKotlin")
    ScannerSubject.assertThat(releaseResult.stdout).contains("androidx.compose.compiler.plugins.kotlin.sourceInformation=false")
  }

  class CompilerOptionsCallback : GenericCallback {
    override fun handleProject(project: Project) {
      val compilerOptions =
        project.extensions.findByType(ComposeCompilerGradlePluginExtension::class.java)
          ?: throw RuntimeException("Could not find extension of type ComposeCompilerGradlePluginExtension")
      compilerOptions.apply { includeSourceInformation.set(false) }
    }
  }
}
