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

package com.android.manifmerger

import com.android.ide.common.blame.SourceFile
import com.android.manifmerger.MergingReport.Record.Severity
import com.android.utils.ILogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions

/** Tests for the [MergingReport] class */
class MergingReportTest {

  private val loggerMock = mock<ILogger>()
  private val sourceLocation = SourceFile("location")

  @Test
  fun justError() {
    val mergingReport = MergingReport.Builder(loggerMock).addMessage(sourceLocation, 0, 0, Severity.ERROR, "Something bad happened").build()

    assertEquals(MergingReport.Result.ERROR, mergingReport.result)
  }

  @Test
  fun justWarning() {
    val mergingReport =
      MergingReport.Builder(loggerMock).addMessage(sourceLocation, 0, 0, Severity.WARNING, "Something weird happened").build()

    assertEquals(MergingReport.Result.WARNING, mergingReport.result)
  }

  @Test
  fun justInfo() {
    val mergingReport = MergingReport.Builder(loggerMock).addMessage(sourceLocation, 0, 0, Severity.INFO, "merging info").build()

    assertEquals(MergingReport.Result.SUCCESS, mergingReport.result)
  }

  @Test
  fun justInfoAndWarning() {
    val mergingReport =
      MergingReport.Builder(loggerMock)
        .addMessage(sourceLocation, 0, 0, Severity.INFO, "merging info")
        .addMessage(sourceLocation, 0, 0, Severity.WARNING, "Something weird happened")
        .build()

    assertEquals(MergingReport.Result.WARNING, mergingReport.result)
  }

  @Test
  fun justInfoAndError() {
    val mergingReport =
      MergingReport.Builder(loggerMock)
        .addMessage(sourceLocation, 0, 0, Severity.INFO, "merging info")
        .addMessage(sourceLocation, 0, 0, Severity.ERROR, "something bad happened")
        .build()

    assertEquals(MergingReport.Result.ERROR, mergingReport.result)
  }

  @Test
  fun justWarningAndError() {
    val mergingReport =
      MergingReport.Builder(loggerMock)
        .addMessage(sourceLocation, 0, 0, Severity.WARNING, "something weird happened")
        .addMessage(sourceLocation, 0, 0, Severity.ERROR, "something bad happened")
        .build()

    assertEquals(MergingReport.Result.ERROR, mergingReport.result)
  }

  @Test
  fun allTypes() {
    val mergingReport =
      MergingReport.Builder(loggerMock)
        .addMessage(sourceLocation, 0, 0, Severity.INFO, "merging info")
        .addMessage(sourceLocation, 0, 0, Severity.WARNING, "something weird happened")
        .addMessage(sourceLocation, 0, 0, Severity.ERROR, "something bad happened")
        .build()

    assertEquals(MergingReport.Result.ERROR, mergingReport.result)
  }

  @Test
  fun logging() {
    val mergingReport =
      MergingReport.Builder(loggerMock)
        .addMessage(sourceLocation, 1, 1, Severity.INFO, "merging info")
        .addMessage(sourceLocation, 1, 1, Severity.WARNING, "something weird happened")
        .addMessage(sourceLocation, 1, 1, Severity.ERROR, "something bad happened")
        .build()

    mergingReport.log(loggerMock)
    verify(loggerMock).verbose("location:1:1 Info:\n\tmerging info")
    verify(loggerMock).warning("location:1:1 Warning:\n\tsomething weird happened")
    verify(loggerMock).error(null, "location:1:1 Error:\n\tsomething bad happened")
    verify(loggerMock).verbose(Actions.HEADER)
    verify(loggerMock)
      .warning(
        "\nSee https://developer.android.com/r/studio-ui/build/manifest-merger " + "for more information about the manifest merger.\n"
      )
    verifyNoMoreInteractions(loggerMock)
  }

  @Test
  fun intermediaryMerges() {
    val mergingReport =
      MergingReport.Builder(loggerMock).addMergingStage("<first/>").addMergingStage("<second/>").addMergingStage("<third/>").build()

    val intermediaryStages = mergingReport.intermediaryStages
    assertEquals(3, intermediaryStages.size)
    assertEquals("<first/>", intermediaryStages[0])
    assertEquals("<second/>", intermediaryStages[1])
    assertEquals("<third/>", intermediaryStages[2])
  }

  @Test
  fun getMergedDocument() {
    val mergingReport = MergingReport.Builder(loggerMock).setMergedDocument(MergingReport.MergedManifestKind.MERGED, "Some String").build()

    assertNotNull(mergingReport.getMergedDocument(MergingReport.MergedManifestKind.MERGED))
    assertEquals("Some String", mergingReport.getMergedDocument(MergingReport.MergedManifestKind.MERGED))
  }
}
