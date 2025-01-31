/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.internal.testing;

import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EmulatorVersionUtilsTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val emulatorDir: File by lazy(LazyThreadSafetyMode.NONE) { tmpFolder.newFolder() }

    @Test
    fun testEmulatorVersionMetadata() {
        val packageFile = emulatorDir.resolve("package.xml")
        packageFile.writeText(
            """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02"
                                xmlns:ns5="http://schemas.android.com/repository/android/generic/02">
                    <localPackage path="emulator" obsolete="false">
                        <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns5:genericDetailsType"/>
                        <revision>
                            <major>31</major>
                            <minor>0</minor>
                            <micro>0</micro>
                        </revision>
                        <display-name>Android Emulator</display-name>
                    </localPackage>
                </ns2:repository>
            """.trimIndent()
        )
        var metadata = getEmulatorMetadata(emulatorDir)
        assertThat(metadata.canUseForceSnapshotLoad).isFalse()

        packageFile.writeText(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02"
                                xmlns:ns5="http://schemas.android.com/repository/android/generic/02">
                    <localPackage path="emulator" obsolete="false">
                        <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns5:genericDetailsType"/>
                        <revision>
                            <major>34</major>
                            <minor>2</minor>
                            <micro>14</micro>
                        </revision>
                        <display-name>Android Emulator</display-name>
                    </localPackage>
                </ns2:repository>
            """.trimIndent()
        )
        metadata = getEmulatorMetadata(emulatorDir)
        assertThat(metadata.canUseForceSnapshotLoad).isTrue()

        packageFile.writeText(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02"
                                xmlns:ns5="http://schemas.android.com/repository/android/generic/02">
                    <localPackage path="emulator" obsolete="false">
                        <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns5:genericDetailsType"/>
                        <revision>
                            <major>30</major>
                            <minor>6</minor>
                            <micro>1</micro>
                        </revision>
                        <display-name>Android Emulator</display-name>
                    </localPackage>
                </ns2:repository>
            """.trimIndent()
        )
        assertThrows(RuntimeException::class.java) {
            metadata = getEmulatorMetadata(emulatorDir)
        }.also {
            assertThat(it).hasMessageThat().contains(
                "Emulator needs to be updated in order to use managed devices."
            )
        }

        packageFile.writeText("")
        assertThrows(Exception::class.java) {
            metadata = getEmulatorMetadata(emulatorDir)
        }.also {
            assertThat(it).hasMessageThat().contains(
                "Could not determine version of Emulator"
            )
        }
    }
}
