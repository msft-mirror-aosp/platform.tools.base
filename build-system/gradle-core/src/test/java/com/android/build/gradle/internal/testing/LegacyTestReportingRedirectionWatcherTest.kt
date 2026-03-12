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

package com.android.build.gradle.internal.testing

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyTestReportingRedirectionWatcherTest {

  @get:Rule val folder = TemporaryFolder()

  @Test
  fun testRedirection() {
    val streamingFile = folder.newFile("streaming.txt")
    val outputStream = ByteArrayOutputStream()
    val printStream = PrintStream(outputStream)

    LegacyTestReportingRedirectionWatcher(streamingFile, printStream).use { watcher ->
      watcher.start()

      streamingFile.appendText("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>event1</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>\n")
      streamingFile.appendText("not an event\n")
      streamingFile.appendText("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>event2</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>\n")

      waitForOutput(outputStream) { it.split("\n").filter { line -> line.isNotBlank() }.size >= 2 }
    }

    val output = outputStream.toString()
    assertThat(output).contains("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>event1</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
    assertThat(output).doesNotContain("not an event")
    assertThat(output).contains("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>event2</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
  }

  @Test
  fun testFinalDrain() {
    val streamingFile = folder.newFile("streaming_drain.txt")
    val outputStream = ByteArrayOutputStream()
    val printStream = PrintStream(outputStream)

    val watcher = LegacyTestReportingRedirectionWatcher(streamingFile, printStream)
    watcher.start()

    streamingFile.appendText("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>event1</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>\n")

    // Immediately close to test final drain if the thread didn't pick it up yet.
    watcher.close()

    val output = outputStream.toString()
    assertThat(output).contains("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>event1</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
  }

  @Test
  fun testMalformedTags() {
    val streamingFile = folder.newFile("malformed.txt")
    val outputStream = ByteArrayOutputStream()
    val printStream = PrintStream(outputStream)

    LegacyTestReportingRedirectionWatcher(streamingFile, printStream).use { watcher ->
      watcher.start()

      // Missing suffix
      streamingFile.appendText("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>missing_suffix\n")
      // Missing prefix
      streamingFile.appendText("missing_prefix</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>\n")
      // Extra content on line
      streamingFile.appendText("extra <UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>event</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>\n")
      // Correct event
      streamingFile.appendText("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>valid</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>\n")

      waitForOutput(outputStream) { it.contains("valid") }
    }

    val output = outputStream.toString()
    assertThat(output).doesNotContain("missing_suffix")
    assertThat(output).doesNotContain("missing_prefix")
    assertThat(output).doesNotContain("extra")
    assertThat(output).contains("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>valid</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
  }

  @Test
  fun testUnexpectedContent() {
    val streamingFile = folder.newFile("unexpected_content.txt")
    val outputStream = ByteArrayOutputStream()
    val printStream = PrintStream(outputStream)

    LegacyTestReportingRedirectionWatcher(streamingFile, printStream).use { watcher ->
      watcher.start()

      streamingFile.appendText("Some random noise\n")
      streamingFile.appendText("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>valid_event</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>\n")
      streamingFile.appendText("More random noise\n")

      waitForOutput(outputStream) { it.contains("valid_event") }
    }

    val output = outputStream.toString()
    assertThat(output.trim()).isEqualTo("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>valid_event</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
  }

  @Test
  fun testNoNewlineAtEnd() {
    val streamingFile = folder.newFile("no_newline.txt")
    val outputStream = ByteArrayOutputStream()
    val printStream = PrintStream(outputStream)

    val watcher = LegacyTestReportingRedirectionWatcher(streamingFile, printStream)
    watcher.start()

    // Write event without newline. reader.readLine() will return the content if it reaches the end of the file.
    streamingFile.appendText("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>no_newline</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")

    // Wait for the watcher to pick it up.
    waitForOutput(outputStream) { it.contains("no_newline") }

    assertThat(outputStream.toString()).contains("no_newline")

    watcher.close()
  }

  @Test
  fun testEarlyClosingBeforeStart() {
    val streamingFile = folder.newFile("early_close_before.txt")
    val outputStream = ByteArrayOutputStream()
    val printStream = PrintStream(outputStream)

    val watcher = LegacyTestReportingRedirectionWatcher(streamingFile, printStream)
    // Close before start. Should not crash and should just return.
    watcher.close()
  }

  @Test
  fun testMultipleEventsSameLine() {
    val streamingFile = folder.newFile("multiple_events.txt")
    val outputStream = ByteArrayOutputStream()
    val printStream = PrintStream(outputStream)

    LegacyTestReportingRedirectionWatcher(streamingFile, printStream).use { watcher ->
      watcher.start()

      // Current implementation redirects the WHOLE line if it starts and ends with the expected tags.
      // If there are multiple events on the same line, they all get redirected together.
      streamingFile.appendText(
        "<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>e1</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT><UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>e2</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>\n"
      )
      streamingFile.appendText("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>e3</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>\n")

      waitForOutput(outputStream) { it.contains("e3") }
    }

    val output = outputStream.toString()
    // It contains e1 and e2 because the line matches the prefix of the first tag and suffix of the last tag.
    assertThat(output).contains("e1")
    assertThat(output).contains("e2")
    assertThat(output).contains("e3")
  }

  @Test
  fun testFileDoesNotExist() {
    val streamingFile = File(folder.root, "does_not_exist.txt")
    val outputStream = ByteArrayOutputStream()
    val printStream = PrintStream(outputStream)

    val watcher = LegacyTestReportingRedirectionWatcher(streamingFile, printStream)
    // start() starts a thread. The thread will fail to open the file.
    // We want to make sure close() still works and doesn't hang.
    watcher.start()
    watcher.close()
  }

  /** Wait a bit for the watcher thread to catch up. Since we use WatchService, it might take a moment depending on the OS. */
  private fun waitForOutput(outputStream: ByteArrayOutputStream, predicate: (String) -> Boolean) {
    var attempts = 0
    while (attempts < 50 && !predicate(outputStream.toString())) {
      Thread.sleep(100)
      attempts++
    }
  }
}
