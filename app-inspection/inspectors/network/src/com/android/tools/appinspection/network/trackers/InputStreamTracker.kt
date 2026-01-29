/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tools.appinspection.network.trackers

import com.android.tools.appinspection.network.reporters.StreamReporter
import java.io.FilterInputStream
import java.io.InputStream

/** Wraps an InputStream to enable the network inspector capturing of response body */
internal class InputStreamTracker(wrapped: InputStream, private val reporter: StreamReporter) : FilterInputStream(wrapped) {

  override fun close() {
    super.close()
    reporter.onStreamClose()
  }

  override fun read(): Int {
    val b = super.read()
    // b is -1 if we've read to stream end
    if (b >= 0) reporter.addOneByte(b)
    reporter.reportCurrentThread()
    return b
  }

  override fun read(buffer: ByteArray, byteOffset: Int, byteCount: Int): Int {
    val bytesRead = super.read(buffer, byteOffset, byteCount)
    // bytesRead is -1 if we've read to stream's end.
    if (bytesRead > 0) {
      reporter.addBytes(buffer, byteOffset, bytesRead)
    }
    reporter.reportCurrentThread()
    return bytesRead
  }

  override fun skip(byteCount: Long): Long {
    reporter.reportCurrentThread()
    if (byteCount < StreamReporter.MAX_BUFFER_SIZE) {
      return read(ByteArray(byteCount.toInt())).toLong()
    } else {
      val skipped = super.skip(byteCount)
      reporter.addBytes("...Skipped $skipped bytes...".toByteArray())
      return skipped
    }
  }
}
