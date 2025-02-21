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
import com.google.common.truth.SimpleSubjectBuilder
import org.junit.Test

class JarWithJavaResourcesSubjectTest: BaseZipSubjectTest() {

    @Test
    fun resources() {
        createJar("temp.jar") {
            addEmptyClasses("com/example/SomeClass", "com/example/SomeOtherClass")
            addTextFile("/somefile.txt", "foo")
            addBinaryFile("/somefile.data", "foo".toByteArray())
        }.use { jar ->

            assertThat(jar) {
                resources().apply {
                    hasSize(2)
                    // should only show the classes and not the other files
                    containsExactly(
                        "somefile.txt",
                        "somefile.data",
                    )
                }
            }

            // test negative results
            expectFailure {
                it.that(jar).resources().hasSize(5)
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "jarWithJavaResources was")
                factValue("value of").isEqualTo("jarWithJavaResources.resources().size()")
                factValue("jarWithJavaResources was").isEqualTo("Zip(name='temp.jar', status=EXISTS)")
            }
        }
    }

    @Test
    fun resourceAsBytes() {
        createJar("temp.jar") {
            addBinaryFile("/path/to/foo.txt", "foo".toByteArray())
        }.use { jar ->

            assertThat(jar) {
                resourceAsBytes("path/to/foo.txt").isEqualTo("foo".toByteArray())
            }

            // test negative results
            expectFailure {
                it.that(jar).resourceAsBytes("path/to/foo.txt").isEqualTo("bar".toByteArray())
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts, we just
                // verify the value name and the name of the 'X was' key.
                factKeys().containsAtLeast("value of", "jarWithJavaResources was")
                factValue("value of").isEqualTo("jarWithJavaResources.resourceAsBytes(path/to/foo.txt)")
                factValue("jarWithJavaResources was").isEqualTo("Zip(name='temp.jar', status=EXISTS)")
            }

            // check querying missing class has right error
            expectFailure {
                it.that(jar).resourceAsBytes("missing.txt")
            }.assert {
                // we want to check for a specific expected/but was here as we want to validate
                // which error is thrown
                factKeys().containsAtLeast("value of", "jarWithJavaResources was", "expected to contain", "but was")
                factValue("value of").isEqualTo("jarWithJavaResources.resources()")
                factValue("expected to contain").isEqualTo("missing.txt")
                factValue("but was").isEqualTo("[path/to/foo.txt]")
                factValue("jarWithJavaResources was").isEqualTo("Zip(name='temp.jar', status=EXISTS)")
            }
        }
    }


    @Test
    fun resourceAsText() {
        createJar("temp.jar") {
            addTextFile("/path/to/foo.txt", "foo")
        }.use { jar ->

            assertThat(jar) {
                resourceAsText("path/to/foo.txt").isEqualTo("foo")
            }

            // test negative results
            expectFailure {
                it.that(jar).resourceAsText("path/to/foo.txt").isEqualTo("bar")
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts, we just
                // verify the value name and the name of the 'X was' key.
                factKeys().containsAtLeast("value of", "jarWithJavaResources was")
                factValue("value of").isEqualTo("jarWithJavaResources.resourceAsText(path/to/foo.txt)")
                factValue("jarWithJavaResources was").isEqualTo("Zip(name='temp.jar', status=EXISTS)")
            }

            // check querying missing class has right error
            expectFailure {
                it.that(jar).resourceAsText("missing.txt")
            }.assert {
                // we want to check for a specific expected/but was here as we want to validate
                // which error is thrown
                factKeys().containsAtLeast("value of", "jarWithJavaResources was", "expected to contain", "but was")
                factValue("value of").isEqualTo("jarWithJavaResources.resources()")
                factValue("expected to contain").isEqualTo("missing.txt")
                factValue("but was").isEqualTo("[path/to/foo.txt]")
                factValue("jarWithJavaResources was").isEqualTo("Zip(name='temp.jar', status=EXISTS)")
            }
        }
    }

    private fun assertThat(zip: Zip, action: ResourcesSubject.() -> Unit) {
        JarWithJavaResourcesSubject.assertThat(zip).apply(action)
    }

    private fun expectFailure(action: (SimpleSubjectBuilder<JarWithJavaResourcesSubject, Zip>) -> Unit): AssertionError {
        return ExpectFailure.expectFailureAbout(JarWithJavaResourcesSubject.jars(), action)
    }
}
