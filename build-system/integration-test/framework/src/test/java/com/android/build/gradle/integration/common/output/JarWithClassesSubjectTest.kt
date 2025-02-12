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

class JarWithClassesSubjectTest: BaseZipSubjectTest() {

    @Test
    fun classes() {
        createJar("temp.jar") {
            addEmptyClasses("com/example/SomeClass", "com/example/SomeOtherClass")
            addTextFile("/somefile.txt", "foo")
            addBinaryFile("/somefile.data", "foo".toByteArray())
        }.use { jar ->

            assertThat(jar) {
                hasSize(2)
                // should only show the classes and not the other files
                containsExactly(
                    "com/example/SomeClass",
                    "com/example/SomeOtherClass",
                )
            }

            // test negative results
            expectFailure {
                it.that(jar).hasSize(5)
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "jarWithClasses was")
                factValue("value of").isEqualTo("jarWithClasses.size()")
                factValue("jarWithClasses was").isEqualTo("Zip(name='temp.jar', status=EXISTS)")
            }
        }
    }

    @Test
    fun classAsBytes() {
        createJar("temp.jar") {
            addBinaryFile("/com/example/ManualClass.class", "foo".toByteArray())
        }.use { jar ->

            assertThat(jar) {
                classAsBytes("com/example/ManualClass").isEqualTo("foo".toByteArray())
            }

            // test negative results
            expectFailure {
                it.that(jar).classAsBytes("com/example/ManualClass").isEqualTo("bar".toByteArray())
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts, we just
                // verify the value name and the name of the 'X was' key.
                factKeys().containsAtLeast("value of", "jarWithClasses was")
                factValue("value of").isEqualTo("jarWithClasses.classAsBytes(com/example/ManualClass)")
                factValue("jarWithClasses was").isEqualTo("Zip(name='temp.jar', status=EXISTS)")
            }

            // check querying missing class has right error
            expectFailure {
                it.that(jar).classAsBytes("com/example/MissingClass")
            }.assert {
                // we want to check for a specific expected/but was here as we want to validate
                // which error is thrown
                factKeys().containsAtLeast("value of", "jarWithClasses was", "expected to contain", "but was")
                factValue("value of").isEqualTo("jarWithClasses.classes()")
                factValue("expected to contain").isEqualTo("com/example/MissingClass")
                factValue("but was").isEqualTo("[com/example/ManualClass]")
                factValue("jarWithClasses was").isEqualTo("Zip(name='temp.jar', status=EXISTS)")
            }
        }
    }

    @Test
    fun classDefinition() {
        createJar("temp.jar") {
            addClassWithEmptyMethods("com/example/SomeClass","foo()V", "bar()Lcom/example/SomeClass;")
        }.use { jar ->

            assertThat(jar) {
                classDefinition("com/example/SomeClass").methods().containsExactly("<init>", "foo", "bar")
            }

            // test negative results
            expectFailure {
                it.that(jar).classDefinition("com/example/SomeClass").methods().contains("missingMethod")
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts, we just
                // verify the value name and the name of the 'X was' key.
                factKeys().containsAtLeast("value of", "jarWithClasses was")
                factValue("value of").isEqualTo("jarWithClasses.classDefinition(com/example/SomeClass).methods()")
                factValue("jarWithClasses was").isEqualTo("Zip(name='temp.jar', status=EXISTS)")
            }

            // check querying missing class has right error
            expectFailure {
                it.that(jar).classDefinition("com/example/MissingClass")
            }.assert {
                // we want to check for a specific expected/but was here as we want to validate
                // which error is thrown
                factKeys().containsAtLeast("value of", "jarWithClasses was", "expected to contain", "but was")
                factValue("value of").isEqualTo("jarWithClasses.classes()")
                factValue("expected to contain").isEqualTo("com/example/MissingClass")
                factValue("but was").isEqualTo("[com/example/SomeClass]")
                factValue("jarWithClasses was").isEqualTo("Zip(name='temp.jar', status=EXISTS)")
            }
        }
    }

    private fun assertThat(zip: Zip, action: ClassesSubject.() -> Unit) {
        JarWithClassesSubject.assertThat(zip).apply(action)
    }

    private fun expectFailure(action: (SimpleSubjectBuilder<JarWithClassesSubject, Zip>) -> Unit): AssertionError {
        return ExpectFailure.expectFailureAbout(JarWithClassesSubject.jars(), action)
    }
}
