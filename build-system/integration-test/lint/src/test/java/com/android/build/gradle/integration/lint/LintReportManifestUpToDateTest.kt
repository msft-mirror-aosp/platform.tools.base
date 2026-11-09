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

package com.android.build.gradle.integration.lint

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * Regression test checking that the lint report task is not UP-TO-DATE after a manifest-only change.
 *
 * Some lint checks (e.g., ManifestDetector's DataExtractionRules check) run in the reporting phase on the merged manifest. The lint model
 * consumed by the report task only contains the merged manifest's path, and the partial results don't change when the edit doesn't touch a
 * resource reference, so the merged manifest itself must be an input of the report task.
 */
class LintReportManifestUpToDateTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication {
      android {
        namespace = "com.example.app"
        defaultConfig {
          // DataExtractionRules only flags a missing android:fullBackupContent when minSdk < 31 <= targetSdk
          minSdk { version = release(26) }
          targetSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
        }
        lint {
          abortOnError = false
          checkOnly += "DataExtractionRules"
          textOutput = projectDotFile("lint-results.txt")
        }
      }
      files {
        update("src/main/AndroidManifest.xml").replaceWith(manifest(withFullBackupContent = true))
        add(
          "src/main/res/xml/data_extraction_rules.xml",
          // language=XML
          """
          <?xml version="1.0" encoding="utf-8"?>
          <data-extraction-rules>
              <cloud-backup />
          </data-extraction-rules>
          """
            .trimIndent(),
        )
      }
    }
  }

  @Test
  fun testLintReportRerunsAfterManifestOnlyChange() {
    val executor = rule.build.executor
    val app = rule.build.androidApplication()
    val lintResults = app.resolve("lint-results.txt")

    // 1. Attribute set: no issue.
    executor.run(":app:lintDebug").apply {
      assertTask(":app:lintAnalyzeDebug").didWork()
      assertTask(":app:lintReportDebug").didWork()
    }
    assertThat(lintResults).doesNotContain(MISSING_FULL_BACKUP_CONTENT)

    // 2. Attribute removed: the merged manifest changes, but neither the report lint model (which only contains the merged manifest's path)
    // nor the partial results change. The report task must still rerun and report the issue.
    app.files.update("src/main/AndroidManifest.xml").replaceWith(manifest(withFullBackupContent = false))
    executor.run(":app:lintDebug").apply {
      assertTask(":app:processDebugMainManifest").didWork()
      assertTask(":app:lintReportDebug").didWork()
    }
    assertThat(lintResults).contains(MISSING_FULL_BACKUP_CONTENT)

    // 3. Attribute restored: the report task must rerun again, and the issue must go away.
    app.files.update("src/main/AndroidManifest.xml").replaceWith(manifest(withFullBackupContent = true))
    executor.run(":app:lintDebug").apply {
      assertTask(":app:lintReportDebug").didWork()
    }
    assertThat(lintResults).doesNotContain(MISSING_FULL_BACKUP_CONTENT)

    // 4. No change: the report task should still be UP-TO-DATE.
    executor.run(":app:lintDebug").apply {
      assertTask(":app:lintReportDebug").wasUpToDate()
    }
  }

  private companion object {

    const val MISSING_FULL_BACKUP_CONTENT = "since minSdkVersion is API 26 you should also set android:fullBackupContent"

    fun manifest(withFullBackupContent: Boolean): String {
      val fullBackupContent = if (withFullBackupContent) " android:fullBackupContent=\"false\"" else ""
      // language=XML
      return """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application android:dataExtractionRules="@xml/data_extraction_rules"$fullBackupContent />
        </manifest>
        """
        .trimIndent()
    }
  }
}
