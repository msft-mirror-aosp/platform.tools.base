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

import com.android.build.gradle.integration.common.output.ZipSubject.Companion.assertThat
import com.google.common.truth.ExpectFailure
import com.google.common.truth.SimpleSubjectBuilder
import org.junit.Test
import java.io.File

class AbstractZipSubjectTest: BaseZipSubjectTest() {

    @Test
    fun entries() {
        createAar("temp.zip") {
            withMainJar {
                addEmptyClasses("com/example/SomeClass")
                addBinaryFile("/file.dat", FAKE_CLASS)
            }
            addResource("values/values.xml", "values file content")
        }.use { zip ->

            assertThat(zip) {
                entries().hasSize(3)
            }

            // checks negative results
            expectFailure {
                it.that(zip).entries().hasSize(5)
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "zip was")
                factValue("value of").isEqualTo("zip.entries().size()")
                factValue("zip was").isEqualTo("Zip(name='temp.zip', status=EXISTS)")
            }
        }
    }

    @Test
    fun innerZip() {
        createAar("temp.zip") {
            withMainJar {
                addEmptyClasses("com/example/SomeClass")
                addBinaryFile("/file.dat", FAKE_CLASS)
            }
            addResource("values/values.xml", "values file content")
        }.use { zip ->

            assertThat(zip) {
                innerZip("classes.jar") {
                    contains("com/example/SomeClass.class")
                    contains("file.dat")
                    binaryFile("file.dat").isEqualTo(FAKE_CLASS)
                }

                // test that you can call the same inner zip multiple times
                // (must actually read some content from the zip and not rely on cache.)
                innerZip("classes.jar") {
                    binaryFile("file.dat").isEqualTo(FAKE_CLASS)
                }
            }

            // checks negative results
            expectFailure {
                it.that(zip).contains("/com/example/SomeOtherClass.class")
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "zip was")
                factValue("value of").isEqualTo("zip.entries()")
                factValue("zip was").isEqualTo("Zip(name='temp.zip', status=EXISTS)")
            }
        }
    }

    @Test
    fun innerZipExistence() {
        createJar("temp.zip") {}.use { zip ->
            assertThat(zip) {
                innerZip("classes.jar").doesNotExist()
            }

            // checks negative results
            expectFailure {
                it.that(zip).innerZip("classes.jar").exists()
            }.assert {
                factKeys().containsExactly("value of", "zip was", "expected to exist")
                factValue("value of").isEqualTo("zip.innerZip(classes.jar)")
                factValue("zip was").isEqualTo("Zip(name='temp.zip', status=EXISTS)")
            }
        }

        createAar("temp2.zip") {
            withMainJar {  }
        }.use { zip ->
            assertThat(zip) {
                innerZip("classes.jar").exists()
            }

            // checks negative results
            expectFailure {
                it.that(zip).innerZip("classes.jar").doesNotExist()
            }.assert {
                factKeys().containsExactly("value of", "zip was", "expected zip to not exist", "but was")
                factValue("value of").isEqualTo("zip.innerZip(classes.jar)")
                factValue("but was").isEqualTo("Zip(name='temp2.zip:classes.jar', status=EXISTS)")
                factValue("zip was").isEqualTo("Zip(name='temp2.zip', status=EXISTS)")
            }
        }
    }

    @Test
    fun testNotExist() {
        SimpleZip(temporaryFolder.newFolder().toPath().resolve("not_exist")).use { missingZip ->
            // check the normal test succeeds
            assertThat(missingZip).doesNotExist()
        }

        // check negative results
        createAar("temp.zip") { }.use { validZip ->
            expectFailure {
                it.that(validZip).doesNotExist()
            }.assert {
                factKeys().containsExactly("expected zip to not exist", "but was")
                factValue("but was").isEqualTo("Zip(name='temp.zip', status=EXISTS)")
            }
        }
    }

    @Test
    fun exist() {
        createAar("temp.zip") { }.use { validZip ->
            // check the normal test succeeds
            assertThat(validZip).exists()
        }

        // check negative results
        SimpleZip(temporaryFolder.newFolder().toPath().resolve("not_exist")).use { missingZip ->
            expectFailure {
                it.that(missingZip).exists()
            }.assert {
                factKeys().containsExactly("expected to exist", "nearest existing ancestor")
                factValue("expected to exist").endsWith("${File.separatorChar}not_exist")
                factValue("nearest existing ancestor").doesNotMatch("^.+${File.separatorChar}not_exist$")
            }
        }
    }

    private fun expectFailure(action: (SimpleSubjectBuilder<ZipSubject, Zip>) -> Unit): AssertionError {
        return ExpectFailure.expectFailureAbout(ZipSubject.zips(), action)
    }
}
