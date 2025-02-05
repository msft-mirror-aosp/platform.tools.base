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

import com.google.common.truth.ExpectFailure
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AbstractZipSubjectTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun zipEntries() {
        val zipPath = temporaryFolder.newFile("temp.zip").toPath()
        TestDataCreator.writeAar(zipPath)

        val zip = Zip(zipPath)
        ZipSubject.assertThat(zip) {
            entries().hasSize(4)
        }

        // checks negative results
        val failure = ExpectFailure.expectFailureAbout(ZipSubject.zips()) {
            it.that(zip).entries().hasSize(5)
        }

        ExpectFailure.assertThat(failure).apply {
            factKeys().containsExactly("value of", "expected", "but was", "zip was")
            factValue("value of").isEqualTo("zip.entries().size()")
            factValue("expected").isEqualTo("5")
            factValue("but was").isEqualTo("4")
            factValue("zip was").isEqualTo("Zip(name='temp.zip', status=EXISTS)")
        }
    }

    @Test
    fun innerZip() {
        val zipPath = temporaryFolder.newFile("temp.zip").toPath()
        TestDataCreator.writeAar(zipPath)

        val zip = Zip(zipPath)
        ZipSubject.assertThat(zip) {
            innerZip("classes.jar") {
                contains("com/example/SomeClass.class")
                binaryFile("com/example/SomeClass.class").isEqualTo(TestDataCreator.FAKE_CLASS)
            }
        }

        // checks negative results
        val failure = ExpectFailure.expectFailureAbout(ZipSubject.zips()) {
            it.that(zip).contains("/com/example/SomeOtherClass.class")
        }

        ExpectFailure.assertThat(failure).apply {
            factKeys().containsExactly("value of", "expected to contain", "but was", "zip was")
            factValue("value of").isEqualTo("zip.entries()")
            factValue("expected to contain").isEqualTo("/com/example/SomeOtherClass.class")
            factValue("but was").isEqualTo("[/res/values/values.xml, /R.txt, /AndroidManifest.xml, /classes.jar]")
            factValue("zip was").isEqualTo("Zip(name='temp.zip', status=EXISTS)")
        }
    }

    @Test
    fun testNotExist() {
        val notExist = temporaryFolder.newFolder().toPath().resolve("not_exist")

        val zip = Zip(notExist)

        // check the normal test succeeds
        ZipSubject.assertThat(zip).doesNotExist()

        // check this opposite
        val failure = ExpectFailure.expectFailureAbout(
            ZipSubject.zips()) { it ->
            it.that(zip).exists()
        }

        ExpectFailure.assertThat(failure).apply {
            factKeys().containsExactly("expected to exist", "nearest existing ancestor")

            File.separatorChar

            factValue("expected to exist").endsWith("${File.separatorChar}not_exist")
            factValue("nearest existing ancestor").doesNotMatch("^.+${File.separatorChar}not_exist$")
        }
    }
}
