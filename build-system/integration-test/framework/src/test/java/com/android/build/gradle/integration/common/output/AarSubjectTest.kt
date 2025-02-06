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

import com.android.build.gradle.integration.common.dependencies.AarBuilder
import com.android.build.gradle.integration.common.dependencies.JarBuilder
import com.android.build.gradle.integration.common.output.AarSubject.Companion.assertThat
import com.google.common.truth.ExpectFailure
import com.google.common.truth.SimpleSubjectBuilder
import org.junit.Test

class AarSubjectTest: BaseZipSubjectTest() {

    @Test
    fun allJars() {
        val aar = createAar("valid.aar") {
            withMainJar {
                addEmptyClasses("com/example/SomeClass", "com/example/SomeOtherClass")
                addTextFile("/somefile.txt", "foo")
                addBinaryFile("/somefile.data", "foo".toByteArray())
            }
            addSecondaryJar("foo") {
                addEmptyClasses("com/foo/SomeClass", "com/foo/SomeOtherClass")
                addTextFile("/foo/file.txt", "foo")
            }
            addSecondaryJar("bar") {
                addEmptyClasses("com/bar/SomeClass")
                addClassWithEmptyMethods("com/bar/SomeOtherClass", "foo()V")
                addBinaryFile("/bar/file.data", "bar".toByteArray())
            }
        }

        assertThat(aar) {
            allJars {
                classes().containsExactly(
                    "com/example/SomeClass",
                    "com/example/SomeOtherClass",
                    "com/foo/SomeClass",
                    "com/foo/SomeOtherClass",
                    "com/bar/SomeClass",
                    "com/bar/SomeOtherClass"
                )
                resources().containsExactly(
                    "somefile.txt",
                    "somefile.data",
                    "foo/file.txt",
                    "bar/file.data"
                )
                classData("com/bar/SomeOtherClass") {
                    methods().containsExactly("<init>", "foo")
                }
                textFile("foo/file.txt").isEqualTo("foo")
                binaryFile("bar/file.data").isEqualTo("bar".toByteArray())
            }
        }

        // test negative results
        expectFailure {
            it.that(aar).allJars().classes().hasSize(5)
        }.assert {
            // we don't care about testing the 'expected' and 'but was' facts
            factKeys().containsAtLeast("value of", "aar was")
            factValue("value of").isEqualTo("aar.allJars().classes().size()")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }

        expectFailure {
            it.that(aar).allJars().classData("com/missing/MissingClass")
        }.assert {
            // we don't care about testing the 'expected' and 'but was' facts
            factKeys().containsAtLeast("value of", "aar was")
            factValue("value of").isEqualTo("aar.allJars().classes()")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }

        // check on empty aars
        val emptyAar = createJar("empty.aar") { }

        assertThat(emptyAar) {
            allJars().classes().isEmpty()
            allJars().resources().isEmpty()
        }
    }

    @Test
    fun mainJar() {
        testJar(
            methodName = "mainJar",
            jarName = "classes.jar",
            jarConfigAction = { withMainJar(it) },
            jarSubjectProvider = { mainJar() }
        )
    }

    @Test
    fun apiJar() {
        testJar(
            methodName = "apiJar",
            jarName = "api.jar",
            jarConfigAction = { withApiJar(it) },
            jarSubjectProvider = { apiJar() }
        )
    }

    @Test
    fun secondaryJars() {
        val aar = createAar("valid.aar") {
            addSecondaryJar("foo") {
                addEmptyClasses("com/foo/SomeClass", "com/foo/SomeOtherClass")
            }
        }

        assertThat(aar) {
            secondaryJars().contains("foo.jar")
        }

        // test negative results
        expectFailure {
            it.that(aar).secondaryJars().hasSize(5)
        }.assert {
            // we don't care about testing the 'expected' and 'but was' facts
            factKeys().containsAtLeast("value of", "aar was")
            factValue("value of").isEqualTo("aar.secondaryJars().size()")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }
    }

    @Test
    fun secondaryJar() {
        val aar = createAar("valid.aar") {
            addSecondaryJar("foo") {
                addEmptyClasses("com/foo/SomeClass", "com/foo/SomeOtherClass")
            }
        }

        assertThat(aar) {
            secondaryJar("foo.jar").classes().contains("com/foo/SomeClass")
        }

        // test negative results
        expectFailure {
            it.that(aar).secondaryJar("foo.jar").classes().contains("com/foo/MissingClass")
        }.assert {
            // we don't care about testing the 'expected' and 'but was' facts
            factKeys().containsAtLeast("value of", "aar was")
            factValue("value of").isEqualTo("aar.secondaryJar(foo.jar).classes()")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }

        // check querying missing jar has right error.
        expectFailure {
            it.that(aar).secondaryJar("missing.jar")
        }.assert {
            // we want to check for a specific expected/but was here as we want to validate
            // which error is thrown
            factKeys().containsAtLeast("value of", "aar was", "expected to contain", "but was")
            factValue("value of").isEqualTo("aar.secondaryJars()")
            factValue("expected to contain").isEqualTo("missing.jar")
            // we want to make sure that the list only contains the /libs folder. This
            // should not contains any other files (e.g. manifest, classes.jar, etc...)
            factValue("but was").isEqualTo("[foo.jar]")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }
    }

    @Test
    fun manifest() {
        val aar = createAar("valid.aar") {
            withManifest("foo")
        }

        assertThat(aar) {
            manifest().isEqualTo("foo")
        }

        // test negative results.
        expectFailure {
            it.that(aar).manifest().contains("bar")
        }.assert {
            // we don't care about testing the 'expected' and 'but was' facts
            factKeys().containsAtLeast("value of", "aar was")
            factValue("value of").isEqualTo("aar.manifest()")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }

        // check querying missing jar has right error.
        // because AarBuilder always creates a manifest and main jar, using jar builder instead
        // to get a truly empty aar
        val emptyAar = createJar("empty.aar") { }

        expectFailure {
            it.that(emptyAar).manifest()
        }.assert {
            // we want to check for a specific expected/but was here as we want to validate
            // which error is thrown
            factKeys().containsAtLeast("value of", "aar was", "expected to contain", "but was")
            factValue("value of").isEqualTo("aar.entries()")
            factValue("expected to contain").isEqualTo("AndroidManifest.xml")
            factValue("but was").isEqualTo("[]")
            factValue("aar was").isEqualTo("Zip(name='empty.aar', status=EXISTS)")
        }
    }

    @Test
    fun androidResources() {
        val aar = createAar("valid.aar") {
            addResource("values/values.xml", "foo")
        }

        assertThat(aar) {
            androidResources().contains("values/values.xml")
        }

        // test negative results
        expectFailure {
            it.that(aar).androidResources().hasSize(5)
        }.assert {
            // we don't care about testing the 'expected' and 'but was' facts
            factKeys().containsAtLeast("value of", "aar was")
            factValue("value of").isEqualTo("aar.androidResources().size()")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }
    }

    @Test
    fun androidResourceAsText() {
        val aar = createAar("valid.aar") {
            withManifest("foo")
            addResource("values/values.xml", "foo")
            addResource("drawable/foo.png", FAKE_CLASS)
        }

        assertThat(aar) {
            androidResourceAsText("values/values.xml").isEqualTo("foo")
        }

        // test negative results.
        expectFailure {
            it.that(aar).androidResourceAsText("values/values.xml").contains("bar")
        }.assert {
            // we don't care about testing the 'expected' and 'but was' facts
            factKeys().containsAtLeast("value of", "aar was")
            factValue("value of").isEqualTo("aar.androidResourceAsText(values/values.xml)")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }

        // check querying missing file has right error.
        expectFailure {
            it.that(aar).androidResourceAsText("values/missing.xml")
        }.assert {
            // we want to check for a specific expected/but was here as we want to validate
            // which error is thrown
            factKeys().containsAtLeast("value of", "aar was", "expected to contain", "but was")
            factValue("value of").isEqualTo("aar.androidResources()")
            factValue("expected to contain").isEqualTo("values/missing.xml")
            // we want to make sure that the list only contains the /res folder. This
            // should not contains any other files (e.g. manifest, classes.jar, etc...)
            factValue("but was").isEqualTo("[drawable/foo.png, values/values.xml]")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }
    }

    @Test
    fun androidResourceAsBytes() {
        val aar = createAar("valid.aar") {
            withManifest("foo")
            addResource("values/values.xml", "foo")
            addResource("drawable/foo.png", FAKE_CLASS)
        }

        assertThat(aar) {
            androidResourceAsBytes("drawable/foo.png").isEqualTo(FAKE_CLASS)
        }

        // test negative results.
        expectFailure {
            it.that(aar).androidResourceAsBytes("drawable/foo.png").isEmpty()
        }.assert {
            // we don't care about testing the 'expected' and 'but was' facts
            factKeys().containsAtLeast("value of", "aar was")
            factValue("value of").isEqualTo("aar.androidResourceAsBytes(drawable/foo.png)")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }

        // check querying missing file has right error.
        expectFailure {
            it.that(aar).androidResourceAsBytes("drawable/missing.png")
        }.assert {
            // we want to check for a specific expected/but was here as we want to validate
            // which error is thrown
            factKeys().containsAtLeast("value of", "aar was", "expected to contain", "but was")
            factValue("value of").isEqualTo("aar.androidResources()")
            factValue("expected to contain").isEqualTo("drawable/missing.png")
            // we want to make sure that the list only contains the /res folder. This
            // should not contains any other files (e.g. manifest, classes.jar, etc...)
            factValue("but was").isEqualTo("[drawable/foo.png, values/values.xml]")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }
    }

    @Test
    fun textSymbolFile() {
        // AarBuilder does not have an API to generate a R.txt, so just use a basic
        // JarBuilder
        val aar = createJar("valid.aar") {
            addTextFile("R.txt", "foo")
        }

        assertThat(aar) {
            textSymbolFile().isEqualTo("foo")
        }

        // test negative results.
        expectFailure {
            it.that(aar).textSymbolFile().contains("bar")
        }.assert {
            // we don't care about testing the 'expected' and 'but was' facts
            factKeys().containsAtLeast("value of", "aar was")
            factValue("value of").isEqualTo("aar.textSymbolFile()")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }

        // check querying missing jar has right error.
        // because AarBuilder always creates a manifest and main jar, using jar builder instead
        // to get a truly empty aar
        val emptyAar = createJar("empty.aar") { }

        expectFailure {
            it.that(emptyAar).textSymbolFile()
        }.assert {
            // we want to check for a specific expected/but was here as we want to validate
            // which error is thrown
            factKeys().containsAtLeast("value of", "aar was", "expected to contain", "but was")
            factValue("value of").isEqualTo("aar.entries()")
            factValue("expected to contain").isEqualTo("R.txt")
            factValue("but was").isEqualTo("[]")
            factValue("aar was").isEqualTo("Zip(name='empty.aar', status=EXISTS)")
        }
    }

    @Test
    fun lintJar() {
        testJar(
            methodName = "lintJar",
            jarName = "lint.jar",
            jarConfigAction = { withLintJar(it) },
            jarSubjectProvider = { lintJar() }
        )
    }

    @Test
    fun aarMetadata() {
        // AarBuilder does not have an API to generate a R.txt, so just use a basic
        // JarBuilder
        val aar = createJar("valid.aar") {
            addTextFile(
                "META-INF/com/android/build/gradle/aar-metadata.properties",
                """
                    aarFormatVersion=1.0
                    aarMetadataVersion=1.1
                    minCompileSdk=1.2
                    minAndroidGradlePluginVersion=1.3
                    forceCompileSdkPreview=1.4
                    minCompileSdkExtension=1.5
                    coreLibraryDesugaringEnabled=1.6
                    desugarJdkLib=1.7
                """.trimIndent())
        }

        assertThat(aar) {
            aarMetadata().formatVersion().isEqualTo("1.0")
            aarMetadata().metadataVersion().isEqualTo("1.1")
            aarMetadata().minCompileSdk().isEqualTo("1.2")
            aarMetadata().minAgpVersion().isEqualTo("1.3")
            aarMetadata().forceCompileSdkPreview().isEqualTo("1.4")
            aarMetadata().minCompileSdkExtension().isEqualTo("1.5")
            aarMetadata().coreLibraryDesugaringEnabled().isEqualTo("1.6")
            aarMetadata().desugarJdkLibId().isEqualTo("1.7")
        }

        // test negative results.
        expectFailure {
            it.that(aar).aarMetadata().formatVersion().isEqualTo("2.0")
        }.assert {
            // we don't care about testing the 'expected' and 'but was' facts
            factKeys().containsAtLeast("value of", "aar was")
            factValue("value of").isEqualTo("aar.aarMetadata().formatVersion()")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }

        // check querying missing jar has right error.
        val emptyAar = createAar("empty.aar") { }

        expectFailure {
            it.that(emptyAar).aarMetadata()
        }.assert {
            // we want to check for a specific expected/but was here as we want to validate
            // which error is thrown
            factKeys().containsAtLeast("value of", "aar was", "expected to contain", "but was")
            factValue("value of").isEqualTo("aar.entries()")
            factValue("expected to contain").isEqualTo("META-INF/com/android/build/gradle/aar-metadata.properties")
            factValue("aar was").isEqualTo("Zip(name='empty.aar', status=EXISTS)")
        }
    }

    /**
     * tests a inner jar.
     *
     * @param methodName the name of the method returning the jar from the AarSubject
     * @param jarName the name of the jar inside the aar
     * @param jarConfigAction the entry point to the jar configuration in [AarBuilder]
     * @param jarSubjectProvider the method that returns the jar in [AarSubject]
     */
    private fun testJar(
        methodName: String,
        jarName: String,
        jarConfigAction: AarBuilder.(JarBuilder.() -> Unit) -> Unit,
        jarSubjectProvider: AarSubject.() -> JarSubject
    ) {
        val aar = createAar("valid.aar") {
            jarConfigAction {
                addEmptyClasses("com/example/SomeClass", "com/example/SomeOtherClass")
                addTextFile("/somefile.txt", "foo")
                addBinaryFile("/somefile.data", "foo".toByteArray())
            }
        }

        assertThat(aar) {
            jarSubjectProvider().classes().hasSize(2)
        }

        // test negative results
        expectFailure {
            it.that(aar).jarSubjectProvider().classes().hasSize(5)
        }.assert {
            // we don't care about testing the 'expected' and 'but was' facts
            factKeys().containsAtLeast("value of", "aar was")
            factValue("value of").isEqualTo("aar.$methodName().classes().size()")
            factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
        }

        // check querying missing jar has right error.
        // because AarBuilder always creates a manifest and main jar, using jar builder instead
        // to get a truly empty aar
        val emptyAar = createJar("empty.aar") { }

        expectFailure {
            it.that(emptyAar).jarSubjectProvider()
        }.assert {
            // we want to check for a specific expected/but was here as we want to validate
            // which error is thrown
            factKeys().containsAtLeast("value of", "aar was", "expected to contain", "but was")
            factValue("value of").isEqualTo("aar.entries()")
            factValue("expected to contain").isEqualTo("$jarName")
            factValue("but was").isEqualTo("[]")
            factValue("aar was").isEqualTo("Zip(name='empty.aar', status=EXISTS)")
        }
    }

    private fun expectFailure(action: (SimpleSubjectBuilder<AarSubject, Zip>) -> Unit): AssertionError {
        return ExpectFailure.expectFailureAbout(AarSubject.aars(), action)
    }
}
