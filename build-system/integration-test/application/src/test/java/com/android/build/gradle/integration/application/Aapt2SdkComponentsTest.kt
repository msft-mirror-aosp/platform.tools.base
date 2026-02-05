/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.internal.res.Aapt2FromMaven.Companion.DefaultAapt2Version
import org.junit.Rule
import org.junit.Test

class Aapt2SdkComponentsTest {

  @get:Rule
  val rule =
    GradleRule.from {
      buildFileType = BuildFileType.KTS
      androidApplication {}
    }

  @Test
  fun testAapt2Tools() {
    val build = rule.build
    build.androidApplication().files.update("build.gradle.kts") {
      append(
        """
                abstract class Aapt2PathTask : DefaultTask() {
                    @get:Nested
                    abstract val aapt2: Property<com.android.build.api.variant.Aapt2>

                    @TaskAction
                    fun execute() {
                        val file = aapt2.get().executable.get().asFile
                        val version = aapt2.get().version.get()
                        if (file.exists().not())
                            throw GradleException("executable file missing")
                        if (version != "${DefaultAapt2Version.VERSION}")
                            throw GradleException("version mismatch")
                    }
                }

                val taskProvider = tasks.register<Aapt2PathTask>("getAapt2Tools") {
                    this.aapt2.set(androidComponents.sdkComponents.aapt2)
                }
            """
          .trimIndent()
      )
    }
    build.executor.run("getAapt2Tools")
  }
}
