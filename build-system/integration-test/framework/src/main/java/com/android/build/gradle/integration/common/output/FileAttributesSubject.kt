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
import java.nio.file.attribute.BasicFileAttributes

class FileAttributesSubject internal constructor(metadata: FailureMetadata, actual: BasicFileAttributes) :
  Subject<FileAttributesSubject, BasicFileAttributes>(metadata, actual) {

  companion object {
    /** Method for getting the subject factory (for use with assertAbout()) */
    internal fun attributes(): Factory<FileAttributesSubject, BasicFileAttributes> {
      return Factory<FileAttributesSubject, BasicFileAttributes> { metadata, actual -> FileAttributesSubject(metadata, actual) }
    }
  }

  fun hasLastModifiedTimeInMillis(value: Long) {
    check("hasLastModifiedTimeInMillis()").that(actual().lastModifiedTime().toMillis()).isEqualTo(value)
  }
}
