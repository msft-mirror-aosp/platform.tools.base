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

import com.google.common.truth.Truth
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

@SuppressWarnings("PathAsIterable")
class ZipTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun checkEmbeddedZip() {
        val zipPath = temporaryFolder.newFile("temp.zip").toPath()
        TestDataCreator.writeAar(zipPath)

        val zip = Zip(zipPath)
        Truth.assertThat(zip.getEntries()).hasSize(4)

        val innerZip = zip.innerZip("classes.jar")
        Truth.assertWithMessage("innerZip(classes.jar)").that(innerZip).isNotNull()

        val classFilePath = innerZip!!.getEntry("com/example/SomeClass.class")
        Truth.assertWithMessage("getEntry(com/example/SomeClass.class)").that(classFilePath).isNotNull()
        Truth.assertThat(Files.readAllBytes(classFilePath!!)).isEqualTo(TestDataCreator.FAKE_CLASS)

        val classFileContent = innerZip.binaryFile("com/example/SomeClass.class")
        Truth.assertWithMessage("binaryFile(com/example/SomeClass.class)").that(classFileContent).isNotNull()
        Truth.assertThat(classFileContent!!).isEqualTo(TestDataCreator.FAKE_CLASS)
    }

    @Test
    fun testNotExist() {
        val notExist = temporaryFolder.newFolder().toPath().resolve("not_exist")

        val zip = Zip(notExist)
        Truth.assertThat(zip.getEntries()).isEmpty()
        Truth.assertThat(zip.exists()).isFalse()
    }

    @Test
    fun invalidFileSystem() {
        val zipPath = temporaryFolder.newFile("temp.zip").toPath()
        TestDataCreator.writeAar(zipPath)

        val zip = Zip(zipPath)
        val entry = zip.getEntry("classes.jar")!!
        Assert.assertNotNull(entry)
        try {
            val innerZip = Zip(entry)
            Assert.fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            Truth.assertThat(e.toString()).contains("getEntryAsZip")
        }
    }
}
