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

import com.android.build.gradle.internal.tasks.AarMetadataReader
import com.google.common.truth.FailureMetadata
import com.google.common.truth.StringSubject
import com.google.common.truth.Subject
import com.google.common.truth.Truth.assertAbout
import java.io.File
import java.nio.file.Path

class AarMetadataSubject internal constructor(
    metadata: FailureMetadata,
    actual: AarMetadataReader
): Subject<AarMetadataSubject, AarMetadataReader>(metadata, actual) {

    companion object {
        fun assertThat(path: Path, action: AarMetadataSubject.() -> Unit) {
            action(assertAbout(aarmetadatas()).that(AarMetadataReader.load(path.toFile())))
        }

        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun aarmetadatas(): Factory<AarMetadataSubject, AarMetadataReader> {
            return Factory<AarMetadataSubject, AarMetadataReader> { metadata, actual ->
                AarMetadataSubject(metadata, actual)
            }
        }
    }

    fun formatVersion(): StringSubject = check("formatVersion()").that(actual().aarFormatVersion)

    fun metadataVersion(): StringSubject = check("metadataVersion()").that(actual().aarMetadataVersion)

    fun minCompileSdk(): StringSubject = check("minCompileSdk()").that(actual().minCompileSdk)

    fun minCompileSdkExtension(): StringSubject = check("minCompileSdkExtension()").that(actual().minCompileSdkExtension)

    fun minAgpVersion(): StringSubject = check("minAgpVersion()").that(actual().minAgpVersion)

    fun forceCompileSdkPreview(): StringSubject = check("forceCompileSdkPreview()").that(actual().forceCompileSdkPreview)

    fun coreLibraryDesugaringEnabled(): StringSubject = check("coreLibraryDesugaringEnabled()").that(actual().coreLibraryDesugaringEnabled)

    fun desugarJdkLibId(): StringSubject = check("desugarJdkLibId()").that(actual().desugarJdkLibId)
}
