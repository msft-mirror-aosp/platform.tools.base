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

package com.android.build.gradle.internal.test.report

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SuiteReportAdapterTest {

  private val registry = SuiteReportAdapterRegistry()

  @Test
  fun screenshotAdapter_matchesPreviewScreenshotPropertiesAndFiltersInternalKeys() {
    val context =
      SuiteAdapterContext(
        rawProperties =
          mapOf(
            "modulePath" to ":app",
            "testSuiteName" to "screenshotTest",
            "testedVariantName" to "debug",
            "testTarget" to "default",
            "PreviewScreenshot.diffPercent" to "1.25",
            "PreviewScreenshot.previewName" to "CardDark",
            "PreviewScreenshot.methodName" to "CardPreviewComposable",
            "PreviewScreenshot.refImagePath" to "src/screenshotTestDebug/reference/card.png",
            "PreviewScreenshot.newImagePath" to "build/outputs/screenshotTest/rendered/card.png",
            "PreviewScreenshot.diffImagePath" to "build/outputs/screenshotTest/diff/card.png",
            "customTag" to "ui",
          )
      )

    val adapter = registry.resolve(context)
    assertThat(adapter).isSameInstanceAs(ScreenshotReportAdapter)

    val adapted = adapter.adapt(context)
    assertThat(adapted.suiteDescriptor).isEqualTo(SuiteDescriptor(adapterId = "screenshot"))
    assertThat(adapted.result.adapterId).isEqualTo("screenshot")
    assertThat(adapted.result.diffPercent).isEqualTo("1.25")
    assertThat(adapted.result.previewName).isEqualTo("CardDark")
    assertThat(adapted.result.methodName).isEqualTo("CardPreviewComposable")
    assertThat(adapted.result.refImagePath).isEqualTo("src/screenshotTestDebug/reference/card.png")
    assertThat(adapted.result.newImagePath).isEqualTo("build/outputs/screenshotTest/rendered/card.png")
    assertThat(adapted.result.diffImagePath).isEqualTo("build/outputs/screenshotTest/diff/card.png")
    assertThat(adapted.result.properties).containsExactly("customTag", "ui")
  }

  @Test
  fun backupRestoreAdapter_formatsTransportAndBuildsCanonicalColumns() {
    val detailRecord =
      TestDetailRecord(
        schemaVersion = 1,
        suiteType = SUITE_TYPE_BACKUP_RESTORE,
        summaryAttributes =
          listOf(
            SummaryAttributeRecord(key = "transport", label = "Transport", value = "DEVICE_TO_DEVICE", filterable = true),
            SummaryAttributeRecord(
              key = "devices",
              label = "Devices",
              value = "Pixel 7 (API 33) -> Pixel 8 (API 35)",
              filterable = true,
            ),
          ),
        callout =
          CalloutInfo(
            kind = "BACKUP_ERROR",
            failedStep = 3,
            title = "Restore verification failed",
            message = "Expected token restored",
            likelyCause = "Excluded by data_extraction_rules.xml",
          ),
        steps = listOf(StepRecord(n = 1, name = "Backup", on = "source", shared = false, durationMs = 120L, status = "PASSED")),
      )
    val context =
      SuiteAdapterContext(
        rawProperties =
          mapOf(
            "BackupRestore.transportMode" to "DEVICE_TO_DEVICE",
            "androidx.report.detailPath" to "suite-details/test1.json",
            "userNote" to "migration",
          ),
        detailRecord = detailRecord,
      )

    val adapter = registry.resolve(context)
    assertThat(adapter).isSameInstanceAs(BackupRestoreReportAdapter)

    val adapted = adapter.adapt(context)
    assertThat(adapted.suiteDescriptor.adapterId).isEqualTo("backupRestore")
    assertThat(adapted.suiteDescriptor.title).isEqualTo("Backup & Restore")
    assertThat(adapted.suiteDescriptor.docsUrl).isEqualTo(BACKUP_RESTORE_DOCS_URL)
    assertThat(adapted.suiteDescriptor.summaryColumns.map { it.key }).containsExactly("transport", "devices").inOrder()
    assertThat(adapted.result.summaryAttributes?.get("transport")?.displayValue).isEqualTo("Device to device")
    assertThat(adapted.result.callout?.likelyCause).isEqualTo("Excluded by data_extraction_rules.xml")
    assertThat(adapted.result.steps).hasSize(1)
    assertThat(adapted.result.properties).containsExactly("userNote", "migration")
  }

  @Test
  fun defaultAdapter_synthesizesFilterableSummaryColumnsForCustomSuites() {
    val detailRecord =
      TestDetailRecord(
        schemaVersion = 1,
        suiteType = "com.example.journey",
        summaryAttributes =
          listOf(
            SummaryAttributeRecord(key = "locale", label = "Locale", value = "en-US", filterable = true),
            SummaryAttributeRecord(key = "traceId", label = "Trace ID", value = "abc-123", filterable = false),
          ),
      )
    val context = SuiteAdapterContext(rawProperties = mapOf("journeyStep" to "checkout"), detailRecord = detailRecord)

    val adapter = registry.resolve(context)
    assertThat(adapter).isSameInstanceAs(DefaultSuiteReportAdapter)

    val adapted = adapter.adapt(context)
    assertThat(adapted.suiteDescriptor.adapterId).isEqualTo("default")
    assertThat(adapted.suiteDescriptor.suiteType).isEqualTo("com.example.journey")
    assertThat(adapted.suiteDescriptor.summaryColumns)
      .containsExactly(SummaryColumnDef(key = "locale", label = "Locale", filterable = true, sortable = true))
    assertThat(adapted.result.summaryAttributes?.keys).containsExactly("locale", "traceId")
    assertThat(adapted.result.properties).containsExactly("journeyStep", "checkout")
  }

  @Test
  fun defaultAdapter_duplicateSummaryAttributeKeysKeepLastEntryConsistently() {
    val detailRecord =
      TestDetailRecord(
        schemaVersion = 1,
        suiteType = "com.example.journey",
        summaryAttributes =
          listOf(
            SummaryAttributeRecord(key = "locale", label = "Old Locale", value = "en", filterable = true),
            SummaryAttributeRecord(key = "locale", label = "Locale", value = "en-US", filterable = true),
            SummaryAttributeRecord(key = "seed", label = "Seed", value = "1", filterable = true),
            SummaryAttributeRecord(key = "seed", label = "Seed", value = "2", filterable = false),
          ),
      )
    val adapted = DefaultSuiteReportAdapter.adapt(SuiteAdapterContext(rawProperties = emptyMap(), detailRecord = detailRecord))

    assertThat(adapted.result.summaryAttributes)
      .containsExactly(
        "locale",
        SummaryAttributeValue(value = "en-US", displayValue = "en-US", label = "Locale"),
        "seed",
        SummaryAttributeValue(value = "2", displayValue = "2", label = "Seed"),
      )
    assertThat(adapted.suiteDescriptor.summaryColumns)
      .containsExactly(SummaryColumnDef(key = "locale", label = "Locale", filterable = true, sortable = true))
  }
}
