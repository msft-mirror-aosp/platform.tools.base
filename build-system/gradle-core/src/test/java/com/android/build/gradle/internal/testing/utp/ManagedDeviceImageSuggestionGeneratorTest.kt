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

package com.android.build.gradle.internal.testing.utp

import com.google.common.truth.Truth.assertThat
import com.android.utils.CpuArchitecture
import org.junit.Test

class ManagedDeviceImageSuggestionGeneratorTest {

    @Test
    fun ensureInvalidAllArchitectureMessage() {
        val generator = ManagedDeviceImageSuggestionGenerator(
            CpuArchitecture.X86_64,
            "invalid_device",
            30,
            0,
            null,
            "aosp",
            "",
            false,
            listOf()
        )

        assertThat(generator.message).isEqualTo(
            "System Image specified by invalid_device does not exist.\n\n" +
                    "Try one of the following fixes:"
        )
    }

    @Test
    fun ensureValidForOtherArchitectureMessage() {
        val generator = ManagedDeviceImageSuggestionGenerator(
            CpuArchitecture.X86_64,
            "possible_valid_device",
            30,
            0,
            null,
            "aosp",
            "",
            false,
            listOf("system-images;android-30;default;arm64-v8a")
        )

        assertThat(generator.message).isEqualTo(
            "System Image for possible_valid_device does not exist for this architecture. " +
                    "However it is valid for ARM. This may be intended, but " +
                    "possible_valid_device cannot be used on this device.\n\n" +
                    "If this is not intended, try one of the following fixes:"
        )
    }

    @Test
    fun ensureATDSuggestionWorks() {
        val generator = ManagedDeviceImageSuggestionGenerator(
            CpuArchitecture.X86_64,
            "atd_device",
            27,
            0,
            null,
            "aosp-atd",
            "",
            false,
            listOf("system-images;android-27;default;x86")
        )

        assertThat(generator.message).isEqualTo(
            "System Image specified by atd_device does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "1. Automated Test Device image does not exist for this architecture on the " +
                    "given sdkVersion. However, a normal emulator image does exist from a " +
                    "comparable source. Set systemImageSource = \"aosp\" to use."
        )
    }

    @Test
    fun ensureAlternativeSourceSuggestionWorks() {
        val generator = ManagedDeviceImageSuggestionGenerator(
            CpuArchitecture.X86_64,
            "another_atd_device",
            28,
            0,
            null,
            "google-atd",
            "",
            true,
            listOf(
                "system-images;android-28;aosp_atd;x86_64",
                "system-images;android-28;default;x86_64"
            )
        )

        assertThat(generator.message).isEqualTo(
            "System Image specified by another_atd_device does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "1. The image does not exist from google-atd for this architecture on the " +
                    "given sdkVersion. However, other sources exist. Set systemImageSource to any " +
                    "of [aosp-atd, aosp] to use."
        )
    }

    @Test
    fun ensureSuggestingHigherApiLevelWorks() {
        val generator = ManagedDeviceImageSuggestionGenerator(
            CpuArchitecture.X86_64,
            "api_not_valid",
            12,
            0,
            null,
            "aosp",
            "",
            false,
            listOf(
                "system-images;android-14;default;x86",
                // Lower api levels should be ignored.
                "system-images;android-11;default;x86"
            )
        )
        assertThat(generator.message).isEqualTo(
            "System Image specified by api_not_valid does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "1. The system image does not exist for sdkVersion 12. However an image exists " +
                    "for sdkVersion 14. Set sdkVersion = 14 to use."
        )
    }

    @Test
    fun ensureSuggestingLatestIfHigherDoesNotExistWorks() {
        val generator = ManagedDeviceImageSuggestionGenerator(
            CpuArchitecture.ARM,
            "too_high_api",
            200,
            0,
            null,
            "aosp",
            "",
            false,
            listOf(
                "system-images;android-31;default;arm64-v8a"
            )
        )
        assertThat(generator.message).isEqualTo(
            "System Image specified by too_high_api does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "1. The system image does not presently exist for sdkVersion 200. The latest " +
                    "available sdkVersion is 31. Set sdkVersion = 31 to use."
        )
    }

    @Test
    fun ensureSuggestingExtensionWorks() {
        assertThat(
            ManagedDeviceImageSuggestionGenerator(
                CpuArchitecture.ARM,
                "extensionWithHigher",
                31,
                0,
                10,
                "google_apis",
                "",
                false,
                listOf("system-images;android-31-ext12;google_apis;arm64-v8a")
            ).message
        ).isEqualTo(
            "System Image specified by extensionWithHigher does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "1. The system image does not exist with extension version 10. However an " +
                    "image exists for extension version 12. Set sdkExtensionVersion = 12 to use."
        )

        assertThat(
            ManagedDeviceImageSuggestionGenerator(
                CpuArchitecture.ARM,
                "extensionNoHigher",
                35,
                0,
                20,
                "google_apis",
                "",
                false,
                listOf("system-images;android-35-ext14;google_apis;arm64-v8a")
            ).message
        ).isEqualTo(
            "System Image specified by extensionNoHigher does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "1. The system image does not presently exist for extension version 20. The " +
                    "latest available extension version for SDK version 35 is 14. Set " +
                    "sdkExtensionVersion = 14 to use. Be aware this may not have all extension " +
                    "apis needed for your application."
        )

        assertThat(
            ManagedDeviceImageSuggestionGenerator(
                CpuArchitecture.ARM,
                "extensionNotAvailable",
                35,
                0,
                10,
                "default",
                "",
                false,
                listOf("system-images;android-35;default;arm64-v8a")
            ).message
        ).isEqualTo(
            "System Image specified by extensionNotAvailable does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "1. No explicit extension levels exist for SDK version 35. Either unset " +
                    "sdkExtensionVersion or try a different sdkVersion."
        )
    }

    @Test
    fun ensurePageAlignmentSuggestionWorks() {
        // check 16k suggests 4k if available
        assertThat(
            ManagedDeviceImageSuggestionGenerator(
                CpuArchitecture.X86_64,
                "page4kbAvailable",
                35,
                0,
                null,
                "default",
                "_ps16k",
                false,
                listOf("system-images;android-35;default;x86_64")
            ).message
        ).isEqualTo(
            "System Image specified by page4kbAvailable does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "1. There is a valid system image for a different page alignment. Set " +
                    "pageAlignment = PageAlignment.FORCE_4KB_PAGES to use. Be aware using " +
                    "a different page alignment will affect how native code is run for " +
                    "testing purposes."
        )

        // check 4k suggests 16k if available.
        assertThat(
            ManagedDeviceImageSuggestionGenerator(
                CpuArchitecture.X86_64,
                "page16kbAvailable",
                35,
                0,
                null,
                "default",
                "",
                false,
                listOf("system-images;android-35;default_ps16k;x86_64")
            ).message
        ).isEqualTo(
            "System Image specified by page16kbAvailable does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "1. There is a valid system image for a different page alignment. Set " +
                    "pageAlignment = PageAlignment.FORCE_16KB_PAGES to use. Be aware using " +
                    "a different page alignment will affect how native code is run for " +
                    "testing purposes."
        )
    }

    @Test
    fun ensure32BitSuggestionWorks() {
        val generator = ManagedDeviceImageSuggestionGenerator(
            CpuArchitecture.X86_64,
            "require_64",
            24,
            0,
            null,
            "aosp",
            "",
            true,
            listOf(
                "system-images;android-24;default;x86"
            )
        )

        assertThat(generator.message).isEqualTo(
            "System Image specified by require_64 does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "1. There is an available X86 image for sdkVersion 24. Set require64Bit = " +
                    "false to use. Be aware tests involving native X86_64 code will not be run " +
                    "with this change."
        )
    }

    @Test
    fun testMultipleSuggestionsWork() {
        val generator = ManagedDeviceImageSuggestionGenerator(
            CpuArchitecture.X86_64,
            "test_device",
            29,
            0,
            null,
            "aosp-atd",
            "",
            true,
            listOf(
                // Valid compatible non-atd image
                "system-images;android-29;default;x86_64",
                // Valid higher api level image
                "system-images;android-31;aosp_atd;x86_64",
                // Valid non require64Bit image, will be skipped because only first 2 suggestions
                // are accepted.
                "system-images;android-29;aosp_atd;x86",
                // Irrelevant images are ignored.
                "system-images;android-29;google_apis;arm64-v8a"
            )
        )

        assertThat(generator.message).isEqualTo(
            "System Image specified by test_device does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "1. Automated Test Device image does not exist for this architecture on the " +
                    "given sdkVersion. However, a normal emulator image does exist from a " +
                    "comparable source. Set systemImageSource = \"aosp\" to use.\n" +
                    "2. The system image does not exist for sdkVersion 29. However an image exists " +
                    "for sdkVersion 31. Set sdkVersion = 31 to use."
        )
    }

    @Test
    fun noSuggestionsRecommendsImageChange() {
        val generator = ManagedDeviceImageSuggestionGenerator(
            CpuArchitecture.X86_64,
            "invalid_source_and_api",
            400,
            0,
            null,
            "foo",
            "",
            false,
            listOf(
                "system-images;android-29;default;x86",
                "system-images;android-31;aosp_atd;x86",
                "system-images;android-29;google_apis;x86",
                "system-images;android-31;google_atd;x86",
                // Wear images are included.
                "system-images;android-30;android-wear;x86",

                // Irrelevant images are ignored.
                "system-images;android-29;google_apis_playstore;arm64-v8a",
            )
        )

        assertThat(generator.message).isEqualTo(
            "System Image specified by invalid_source_and_api does not exist.\n\n" +
                    "Try one of the following fixes:\n" +
                    "Could not form a valid suggestion for the device invalid_source_and_api.\n" +
                    "This is likely due to an invalid image source. The source specified by " +
                    "invalid_source_and_api is \"foo\".\n" +
                    "Set systemImageSource to any of [default, aosp_atd, google_apis, " +
                    "google_atd, android-wear, aosp, aosp-atd, google, google-atd] to get more suggestions."
        )
    }
}
