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

package com.android.tools.screenshot.xml

import com.android.tools.screenshot.PreviewScreenshotTestEngineInput.XmlReportInput
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.reporting.FileEntry
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

// Note that delegation in Kotlin doesn't work with Java interface which has default
// implementation: https://youtrack.jetbrains.com/issue/KT-18324
class XmlReportGeneratingListener : TestExecutionListener {
  private val delegate: TestExecutionListener =
    if (XmlReportInput.isEnabled) {
      LegacyXmlReportGeneratingListener()
    } else {
      object : TestExecutionListener {}
    }

  override fun testPlanExecutionStarted(testPlan: TestPlan) {
    delegate.testPlanExecutionStarted(testPlan)
  }

  override fun testPlanExecutionFinished(testPlan: TestPlan) {
    delegate.testPlanExecutionFinished(testPlan)
  }

  override fun dynamicTestRegistered(testIdentifier: TestIdentifier) {
    delegate.dynamicTestRegistered(testIdentifier)
  }

  override fun executionSkipped(testIdentifier: TestIdentifier, reason: String) {
    delegate.executionSkipped(testIdentifier, reason)
  }

  override fun executionStarted(testIdentifier: TestIdentifier) {
    delegate.executionStarted(testIdentifier)
  }

  override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
    delegate.executionFinished(testIdentifier, testExecutionResult)
  }

  override fun reportingEntryPublished(testIdentifier: TestIdentifier, entry: ReportEntry) {
    delegate.reportingEntryPublished(testIdentifier, entry)
  }

  override fun fileEntryPublished(testIdentifier: TestIdentifier, file: FileEntry) {
    delegate.fileEntryPublished(testIdentifier, file)
  }
}
