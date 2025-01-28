/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.sdklib

import junit.framework.TestCase

class AndroidVersionUtilsTest : TestCase() {

    fun testGetApiNameAndDetails() {
        assertEquals(
            NameDetails("API 16", "\"Jelly Bean\"; Android 4.1"),
            AndroidVersion(16).getApiNameAndDetails(
                includeReleaseName = true,
                includeCodeName = true,
            )
        )

        assertEquals(
            NameDetails("API 16", "\"Jelly Bean\"; Android 4.1"),
            AndroidVersion(
                /* apiLevel = */ 16,
                /* codename = */ null,
                /* extensionLevel = */ 14,
                /* isBaseExtension = */ true
            ).getApiNameAndDetails(
                includeReleaseName = true,
                includeCodeName = true,
            )
        )

        assertEquals(
            NameDetails("API 20 ext. 4", "\"KitKat Wear\"; Android 4.4W"),
            AndroidVersion(20).withExtensionLevel(4).getApiNameAndDetails(
                includeReleaseName = true,
                includeCodeName = true,
            )
        )

        assertEquals(
            NameDetails("API 25", "Android 7.1.1"),
            AndroidVersion(25).getApiNameAndDetails(
                includeReleaseName = true,
                includeCodeName = false,
            )
        )

        assertEquals(
            NameDetails("API 27", "\"Oreo\""),
            AndroidVersion(27).getApiNameAndDetails(
                includeReleaseName = false,
                includeCodeName = true,
            )
        )

        assertEquals(
            NameDetails("API 28", null),
            AndroidVersion(28).getApiNameAndDetails(
                includeReleaseName = false,
                includeCodeName = false,
            )
        )

        assertEquals(
            NameDetails("API 29 ext. 4", null),
            AndroidVersion(29).withExtensionLevel(4).getApiNameAndDetails(
                includeReleaseName = false,
                includeCodeName = false,
            )
        )

        assertEquals(
            NameDetails("API 33.1", null),
            AndroidVersion(33, 1).getApiNameAndDetails(
                includeReleaseName = false,
                includeCodeName = false,
            )
        )

        assertEquals(
            NameDetails("API 36.1", null),
            AndroidVersion(36, 1).getApiNameAndDetails(
                includeReleaseName = false,
                includeCodeName = false,
            )
        )

        assertEquals(
            NameDetails("API 36.1 ext. 31", null),
            AndroidVersion(36, 1).withExtensionLevel(31).getApiNameAndDetails(
                includeReleaseName = false,
                includeCodeName = false,
            )
        )

        assertEquals(
            NameDetails("API Foo Preview", null),
            AndroidVersion(99, "Foo").getApiNameAndDetails(
                includeReleaseName = true,
                includeCodeName = true,
            )
        )

        // Future: if we don't have a name, don't include "null" as a name
        assertEquals(NameDetails("API 500.0", null), AndroidVersion(500).getApiNameAndDetails())
        assertEquals(NameDetails("API 500.0 ext. 12", null), AndroidVersion(500).withExtensionLevel(12).getApiNameAndDetails())
    }

    fun testGetFullApiName() {
        assertEquals("API 16 (\"Jelly Bean\"; Android 4.1)", AndroidVersion(16).getFullApiName(
            includeReleaseName = true,
            includeCodeName = true,
        ))

        assertEquals("API 28", AndroidVersion(28).getFullApiName(
            includeReleaseName = false,
            includeCodeName = false,
        ))

        assertEquals("API 36.0", AndroidVersion(36).getFullApiName())

        assertEquals("API 36.1", AndroidVersion(36, 1).getFullApiName())

        assertEquals(
            "API 16 ext. 14 (\"Jelly Bean\"; Android 4.1)",
            AndroidVersion(
                /* apiLevel = */ 16,
                /* codename = */ null,
                /* extensionLevel = */ 14,
                /* isBaseExtension = */ false
            ).getFullApiName(
                includeReleaseName = true,
                includeCodeName = true,
            )
        )

        assertEquals(
            "API 16 (\"Jelly Bean\"; Android 4.1)",
            AndroidVersion(
                /* apiLevel = */ 16,
                /* codename = */ null,
                /* extensionLevel = */ 14,
                /* isBaseExtension = */ true
            ).getFullApiName(
                includeReleaseName = true,
                includeCodeName = true,
            )
        )

        assertEquals(
            "API Foo Preview",
            AndroidVersion(99, "Foo").getFullApiName(
                includeReleaseName = true,
                includeCodeName = true,
            )
        )
    }

    fun testGetReleaseNameAndDetails() {
        assertEquals(
            NameDetails("Android 4.1", "\"Jelly Bean\"; API 16"),
            AndroidVersion(16).getReleaseNameAndDetails(
                includeApiLevel = true,
                includeCodeName = true,
            )
        )

        assertEquals(
            NameDetails("Android 4.1", "\"Jelly Bean\"; API 16"),
            AndroidVersion(
                /* apiLevel = */ 16,
                /* codename = */ null,
                /* extensionLevel = */ 14,
                /* isBaseExtension = */ true
            ).getReleaseNameAndDetails(
                includeApiLevel = true,
                includeCodeName = true,
            )
        )

        assertEquals(
            NameDetails("Android 4.1", "\"Jelly Bean\"; API 16 ext. 14"),
            AndroidVersion(
                /* apiLevel = */ 16,
                /* codename = */ null,
                /* extensionLevel = */ 14,
                /* isBaseExtension = */ false
            ).getReleaseNameAndDetails(
                includeApiLevel = true,
                includeCodeName = true,
            )
        )

        assertEquals(
            NameDetails("Android 4.4W", "\"KitKat Wear\"; API 20 ext. 4"),
            AndroidVersion(20).withExtensionLevel(4).getReleaseNameAndDetails(
                includeApiLevel = true,
                includeCodeName = true,
            )
        )

        assertEquals(
            NameDetails("Android 7.1.1", "API 25"),
            AndroidVersion(25).getReleaseNameAndDetails(
                includeApiLevel = true,
                includeCodeName = false,
            )
        )

        assertEquals(
            NameDetails("Android 8.1", "\"Oreo\""),
            AndroidVersion(27).getReleaseNameAndDetails(
                includeApiLevel = false,
                includeCodeName = true,
            )
        )

        assertEquals(
            NameDetails("Android 9.0", null),
            AndroidVersion(28).withExtensionLevel(4).getReleaseNameAndDetails(
                includeApiLevel = false,
                includeCodeName = false,
            )
        )

        assertEquals(
            NameDetails("Android 9.0", "\"Pie\""),
            AndroidVersion(28).withExtensionLevel(4).getReleaseNameAndDetails(
                includeApiLevel = false,
                includeCodeName = true,
            )
        )

        assertEquals(
            NameDetails("Android 14.0", "\"UpsideDownCake\"; API 34.1"),
            AndroidVersion(34, 1).getReleaseNameAndDetails(includeApiLevel = true, includeCodeName = true)
        )

        assertEquals(
            NameDetails("Android API 99.1", null),
            AndroidVersion(99, 1).getReleaseNameAndDetails(includeApiLevel = true, includeCodeName = true)
        )

        assertEquals(
            NameDetails("Android Foo Preview", null),
            AndroidVersion(99, "Foo").getReleaseNameAndDetails(
                includeApiLevel = true,
                includeCodeName = true,
            )
        )

        // Future: if we don't have a name, don't include "null" as a name
        assertEquals(NameDetails("Android API 500.0", null), AndroidVersion(500).getReleaseNameAndDetails())
        assertEquals(NameDetails("Android API 500.0", null), AndroidVersion(500).withExtensionLevel(12).getReleaseNameAndDetails())
    }


    fun testGetFullReleaseName() {
        assertEquals("Android 4.1 (\"Jelly Bean\"; API 16)",
                     AndroidVersion(16).getFullReleaseName(
            includeApiLevel = true,
            includeCodeName = true,
        ))

        assertEquals(
            "Android 4.1 (\"Jelly Bean\"; API 16 ext. 14)",
            AndroidVersion(
                /* apiLevel = */ 16,
                /* codename = */ null,
                /* extensionLevel = */ 14,
                /* isBaseExtension = */ false
            ).getFullReleaseName(
                includeApiLevel = true,
                includeCodeName = true,
            )
        )

        assertEquals(
            "Android 4.1 (\"Jelly Bean\"; API 16)",
            AndroidVersion(
                /* apiLevel = */ 16,
                /* codename = */ null,
                /* extensionLevel = */ 14,
                /* isBaseExtension = */ true
            ).getFullReleaseName(
                includeApiLevel = true,
                includeCodeName = true,
            )
        )

        assertEquals("Android 9.0", AndroidVersion(28).withExtensionLevel(4).getFullReleaseName(
            includeApiLevel = false,
            includeCodeName = false,
        ))

        assertEquals(
            "Android 14.0 (\"UpsideDownCake\"; API 34.1)",
            AndroidVersion(34, 1).getFullReleaseName(includeApiLevel = true, includeCodeName = true)
        )

        assertEquals(
            "Android API 99.1",
            AndroidVersion(99, 1).getFullReleaseName(includeApiLevel = true, includeCodeName = true)
        )

        assertEquals(
            "Android Foo Preview",
            AndroidVersion(99, "Foo").getFullReleaseName(
                includeApiLevel = true,
                includeCodeName = true,
            )
        )
    }
}
