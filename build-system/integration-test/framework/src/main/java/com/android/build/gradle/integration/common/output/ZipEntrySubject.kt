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

package com.android.build.gradle.integration.common.output

import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import java.util.zip.ZipEntry

/**
 * Truth subject to validate the metadata of a [ZipEntry]
 *
 * This is returned by [ZipSubject.zipEntry]
 */
class ZipEntrySubject internal constructor(metadata: FailureMetadata, actual: ZipEntry) :
  Subject<ZipEntrySubject, ZipEntry>(metadata, actual) {

  companion object {
    /** Method for getting the subject factory (for use with assertAbout()) */
    internal fun zipEntries(): Factory<ZipEntrySubject, ZipEntry> {
      return Factory<ZipEntrySubject, ZipEntry> { metadata, actual -> ZipEntrySubject(metadata, actual) }
    }
  }

  fun hasCompressionMethod(method: Int) {
    check("hasCompressionMethod()").that(actual().method).isEqualTo(method)
  }
}
