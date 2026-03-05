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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import org.junit.Rule
import org.junit.Test

class BootClasspathPrematureAccessTest {

  @get:Rule
  val rule =
    GradleRule.from {
      buildFileType = BuildFileType.KTS
      androidApplication {}
    }

  @Test
  fun `test bootClasspath access before finalization`() {
    val build = rule.build
    build
      .androidApplication()
      .files
      .update("build.gradle.kts")
      .append(
        """
        val customFiles = objects.fileCollection()
        customFiles.from(androidComponents.sdkComponents.bootClasspath)

        tasks.register("fooTask") {
          val customFilesInside = customFiles
          inputs.files(customFilesInside)
          doLast {
            println(customFilesInside.files)
          }
        }
        """
          .trimIndent()
      )

    build.executor.run(":app:fooTask")
  }
}
