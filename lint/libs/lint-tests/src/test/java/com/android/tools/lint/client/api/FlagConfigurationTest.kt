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
package com.android.tools.lint.client.api

import com.android.tools.lint.checks.AbstractCheckTest
import com.android.tools.lint.checks.SdCardDetector
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Severity
import org.junit.Assert.assertEquals

class FlagConfigurationTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return SdCardDetector()
  }

  fun testEnableIssueWithSuppressAnnotations() {
    val client = createClient()
    val myIssue =
      Issue.create(
        id = "MyCustomIssue",
        briefDescription = "My custom issue",
        explanation = "My custom issue explanation",
        implementation = SdCardDetector.ISSUE.implementation,
        severity = Severity.WARNING,
        enabledByDefault = false,
        suppressAnnotations = listOf("com.example.MySuppress"),
      )

    val configuration =
      object : FlagConfiguration(client.configurations) {
        override fun enabledIds(): Set<String> = setOf("MyCustomIssue")
      }

    assertEquals(Severity.WARNING, configuration.getSeverity(myIssue))
  }

  fun testDisableIssueWithSuppressAnnotations() {
    val client = createClient()
    val myIssue =
      Issue.create(
        id = "MyCustomIssue",
        briefDescription = "My custom issue",
        explanation = "My custom issue explanation",
        implementation = SdCardDetector.ISSUE.implementation,
        severity = Severity.WARNING,
        enabledByDefault = true,
        suppressAnnotations = listOf("com.example.MySuppress"),
      )

    val configuration =
      object : FlagConfiguration(client.configurations) {
        override fun disabledIds(): Set<String> = setOf("MyCustomIssue")
      }

    // Should not be allowed to suppress it, so it remains WARNING
    assertEquals(Severity.WARNING, configuration.getSeverity(myIssue))
  }
}
