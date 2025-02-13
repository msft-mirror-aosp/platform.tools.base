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
import com.google.common.truth.Truth.assertAbout

/**
 * Generic Zip archive Truth subject
 */
class ZipSubject(
    metadata: FailureMetadata,
    actual: Zip
): AbstractZipSubject<ZipSubject, Zip>(metadata, actual) {

    companion object {
        /**
         * Returns a [com.android.build.gradle.integration.common.output.ZipSubject]
         */
        fun assertThat(zip: Zip): ZipSubject {
            return assertAbout(zips()).that(zip)
        }

        /**
         * Creates a [com.android.build.gradle.integration.common.output.ZipSubject] and
         * configures it with the given action
         */
        fun assertThat(zip: Zip, action: ZipSubject.() -> Unit) {
            action(assertThat(zip))
        }

        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun zips(): Factory<ZipSubject, Zip> {
            return Factory<ZipSubject, Zip> { metadata, actual ->
                ZipSubject(metadata, actual)
            }
        }
    }
}
