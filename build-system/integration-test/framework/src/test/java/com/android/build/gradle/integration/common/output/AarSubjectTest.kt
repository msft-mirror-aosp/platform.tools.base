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
import org.jetbrains.annotations.CheckReturnValue
import org.junit.Test
import kotlin.use

@Suppress("UnstableApiUsage")
class AarSubjectTest: BaseZipSubjectTest() {

    @Test
    fun classes() {
        createAarWithCodeAndJavaResources().use { aar ->
            assertThat(aar) {
                classes().containsExactly(
                    "com/example/SomeClass",
                    "com/example/SomeOtherClass",
                    "com/foo/SomeClass",
                    "com/foo/SomeOtherClass",
                    "com/bar/SomeClass",
                    "com/bar/SomeOtherClass"
                )

                // test that you can call the same inner zip (possibly via a different API)
                // multiple times
                // (must actually read some content from the zip and not rely on cache.)
                mainJar {
                    classes().containsExactly("com/example/SomeClass", "com/example/SomeOtherClass")
                }
            }

            // test negative results
            expectFailure {
                it.that(aar).classes().hasSize(5)
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was")
                factValue("value of").isEqualTo("aar.classes().size()")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
            expectFailure {
                it.that(aar).classes().isEmpty()
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was", "expected to be empty")
                factValue("value of").isEqualTo("aar.classes().entries()")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
            expectFailure {
                it.that(aar).classes().containsExactly("foo/bar")
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was")
                factValue("value of").isEqualTo("aar.classes().entries()")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
        }

        // check on empty aars
        createJar("empty.aar") { }.use { emptyAar ->
            assertThat(emptyAar) {
                this.classes().isEmpty()
            }
        }
    }

    @Test
    fun classDefinition() {
        createAarWithCodeAndJavaResources().use { aar ->
            assertThat(aar) {
                classes {
                    // make sure we can read all classes from all jars
                    classDefinition("com/example/SomeClass") {
                        methods().containsExactly("<init>", "foo")
                    }
                    classDefinition("com/foo/SomeClass") {
                        methods().containsExactly("<init>", "foo")
                    }
                    classDefinition("com/bar/SomeClass") {
                        methods().containsExactly("<init>", "foo")
                    }
                }

                // test that you can call the same inner zip (possibly via a different API)
                // multiple times
                // (must actually read some content from the zip and not rely on cache.)
                mainJar {
                    classes().classDefinition("com/example/SomeClass") {
                        methods().containsExactly("<init>", "foo")
                    }
                }
            }

            // test negative results
            expectFailure {
                it.that(aar).classes().classDefinition("com/missing/MissingClass")
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was")
                factValue("value of").isEqualTo("aar.classes().classes()")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
        }
    }

    @Test
    fun secondaryJars() {
        createAarWithCodeAndJavaResources().use { aar ->
            assertThat(aar) {
                secondaryJars {
                    classes {
                        containsExactly(
                            "com/foo/SomeClass",
                            "com/foo/SomeOtherClass",
                            "com/bar/SomeClass",
                            "com/bar/SomeOtherClass"
                        )
                        classDefinition("com/bar/SomeClass") {
                            methods().containsExactly("<init>", "foo")
                        }
                    }
                    resources {
                        containsExactly(
                            "foo/file.txt",
                            "bar/file.data"
                        )
                        resourceAsText("foo/file.txt").isEqualTo("foo")
                        resourceAsBytes("bar/file.data").isEqualTo("bar".toByteArray())
                    }
                }
            }

            // test negative results
            expectFailure {
                it.that(aar).secondaryJars().classes().hasSize(5)
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was")
                factValue("value of").isEqualTo("aar.secondaryJars().size()")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }

            expectFailure {
                it.that(aar).secondaryJars().classes().classDefinition("com/missing/MissingClass")
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was")
                factValue("value of").isEqualTo("aar.secondaryJars().classes()")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
        }

        // check on empty aars
        createJar("empty.aar") { }.use { emptyAar ->

            assertThat(emptyAar) {
                secondaryJars().classes().isEmpty()
                secondaryJars().resources().isEmpty()
            }
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
        createAar("valid.aar") {
            withApiJar {
                addClassWithEmptyMethods("com/example/SomeClass", "foo()V")
                addClassWithEmptyMethods("com/example/SomeOtherClass", "bar()V")
            }
        }.use { aar ->

            assertThat(aar) {
                apiJar().hasSize(2)
            }

            // test negative results
            expectFailure {
                it.that(aar).apiJar().hasSize(5)
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was")
                factValue("value of").isEqualTo("aar.apiJar().size()")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
        }

        // check querying missing jar has right error.
        // because AarBuilder always creates a manifest and main jar, using jar builder instead
        // to get a truly empty aar
        createJar("empty.aar") { }.use { emptyAar ->
            expectFailure {
                it.that(emptyAar).apiJar()
            }.assert {
                // we want to check for a specific expected/but was here as we want to validate
                // which error is thrown
                factKeys().containsAtLeast("value of", "aar was", "expected to contain", "but was")
                factValue("value of").isEqualTo("aar.entries()")
                factValue("expected to contain").isEqualTo("api.jar")
                factValue("but was").isEqualTo("[]")
                factValue("aar was").isEqualTo("Zip(name='empty.aar', status=EXISTS)")
            }
        }
    }

    @Test
    fun javaResources() {
        createAarWithCodeAndJavaResources().use { aar ->
            assertThat(aar) {
                javaResources().apply {
                    containsExactly(
                        "somefile.txt",
                        "somefile.data",
                        "foo/file.txt",
                        "bar/file.data"
                    )
                    resourceAsText("foo/file.txt").isEqualTo("foo")
                    resourceAsBytes("bar/file.data").isEqualTo("bar".toByteArray())
                }

                // test that you can call the same inner zip (possibly via a different API)
                // multiple times
                // (must actually read some content from the zip and not rely on cache.)
                mainJar {
                    resources().resourceAsText("somefile.txt").isEqualTo("foo")
                }
            }

            // test negative results
            expectFailure {
                it.that(aar).javaResources().hasSize(5)
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was")
                factValue("value of").isEqualTo("aar.javaResources().size()")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
            expectFailure {
                it.that(aar).javaResources().isEmpty()
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was", "expected to be empty")
                factValue("value of").isEqualTo("aar.javaResources().entries()")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
        }

        // check on empty aars
        createJar("empty.aar") { }.use { emptyAar ->
            assertThat(emptyAar) {
                javaResources().isEmpty()
            }
        }
    }

    @Test
    fun manifest() {
        createAar("valid.aar") {
            withManifest("foo")
        }.use { aar ->

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
        }

        // check querying missing jar has right error.
        // because AarBuilder always creates a manifest and main jar, using jar builder instead
        // to get a truly empty aar
        createJar("empty.aar") { }.use { emptyAar ->

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
    }

    @Test
    fun androidResources() {
        createAar("valid.aar") {
            addResource("values/values.xml", "foo")
        }.use { aar ->

            assertThat(aar) {
                androidResources().containsExactly("values/values.xml")
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
    }

    @Test
    fun androidResource_resourceAsText() {
        createAar("valid.aar") {
            withManifest("foo")
            addResource("values/values.xml", "foo")
            addResource("drawable/foo.png", FAKE_CLASS)
        }.use { aar ->
            assertThat(aar) {
                androidResources().resourceAsText("values/values.xml").isEqualTo("foo")
            }

            // test negative results.
            expectFailure {
                it.that(aar).androidResources().resourceAsText("values/values.xml").contains("bar")
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was")
                factValue("value of").isEqualTo("aar.androidResources().resourceAsText(values/values.xml)")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }

            // check querying missing file has right error.
            expectFailure {
                it.that(aar).androidResources().resourceAsText("values/missing.xml")
            }.assert {
                // we want to check for a specific expected/but was here as we want to validate
                // which error is thrown
                factKeys().containsAtLeast("value of", "aar was", "expected to contain", "but was")
                factValue("value of").isEqualTo("aar.androidResources().entries()")
                factValue("expected to contain").isEqualTo("values/missing.xml")
                // we want to make sure that the list only contains the /res folder. This
                // should not contains any other files (e.g. manifest, classes.jar, etc...)
                factValue("but was").isEqualTo("[drawable/foo.png, values/values.xml]")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
        }
    }

    @Test
    fun androidResource_resourceAsBytes() {
        createAar("valid.aar") {
            withManifest("foo")
            addResource("values/values.xml", "foo")
            addResource("drawable/foo.png", FAKE_CLASS)
        }.use { aar ->

            assertThat(aar) {
                androidResources().resourceAsBytes("drawable/foo.png").isEqualTo(FAKE_CLASS)
            }

            // test negative results.
            expectFailure {
                it.that(aar).androidResources().resourceAsBytes("drawable/foo.png").isEmpty()
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was")
                factValue("value of").isEqualTo("aar.androidResources().resourceAsBytes(drawable/foo.png)")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }

            // check querying missing file has right error.
            expectFailure {
                it.that(aar).androidResources().resourceAsBytes("drawable/missing.png")
            }.assert {
                // we want to check for a specific expected/but was here as we want to validate
                // which error is thrown
                factKeys().containsAtLeast("value of", "aar was", "expected to contain", "but was")
                factValue("value of").isEqualTo("aar.androidResources().entries()")
                factValue("expected to contain").isEqualTo("drawable/missing.png")
                // we want to make sure that the list only contains the /res folder. This
                // should not contains any other files (e.g. manifest, classes.jar, etc...)
                factValue("but was").isEqualTo("[drawable/foo.png, values/values.xml]")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
        }
    }

    @Test
    fun assets() {
        createAar("valid.aar") {
            addAsset("foo.txt", "foo")
            addAsset("bar.txt", "bar")
            addAsset("bar.data", FAKE_CLASS)
        }.use { aar ->

            assertThat(aar) {
                assets().containsExactly("foo.txt", "bar.txt", "bar.data")
            }

            // test negative results
            expectFailure {
                it.that(aar).assets().hasSize(5)
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was")
                factValue("value of").isEqualTo("aar.assets().size()")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
        }
    }


    @Test
    fun textSymbolFile() {
        // AarBuilder does not have an API to generate a R.txt, so just use a basic
        // JarBuilder
        createJar("valid.aar") {
            addTextFile("R.txt", "foo")
        }.use { aar ->

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
        }

        // check querying missing jar has right error.
        // because AarBuilder always creates a manifest and main jar, using jar builder instead
        // to get a truly empty aar
        createJar("empty.aar") { }.use { emptyAar ->
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
        createJar("valid.aar") {
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
        }.use { aar ->

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
        }

        // check querying missing jar has right error.
        createAar("empty.aar") { }.use { emptyAar ->
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
        createAar("valid.aar") {
            jarConfigAction {
                addEmptyClasses("com/example/SomeClass", "com/example/SomeOtherClass")
                addTextFile("/somefile.txt", "foo")
                addBinaryFile("/somefile.data", "foo".toByteArray())
            }
        }.use { aar ->

            assertThat(aar) {
                jarSubjectProvider().classes().hasSize(2)
            }

            // test negative results
            expectFailure {
                it.that(aar).jarSubjectProvider().classes().hasSize(5)
            }.assert {
                // we don't care about testing the 'expected' and 'but was' facts
                factKeys().containsAtLeast("value of", "aar was")
                factValue("value of").isEqualTo("aar.$methodName().size()")
                factValue("aar was").isEqualTo("Zip(name='valid.aar', status=EXISTS)")
            }
        }

        // check querying missing jar has right error.
        // because AarBuilder always creates a manifest and main jar, using jar builder instead
        // to get a truly empty aar
        createJar("empty.aar") { }.use { emptyAar ->
            expectFailure {
                it.that(emptyAar).jarSubjectProvider()
            }.assert {
                // we want to check for a specific expected/but was here as we want to validate
                // which error is thrown
                factKeys().containsAtLeast("value of", "aar was", "expected to contain", "but was")
                factValue("value of").isEqualTo("aar.entries()")
                factValue("expected to contain").isEqualTo(jarName)
                factValue("but was").isEqualTo("[]")
                factValue("aar was").isEqualTo("Zip(name='empty.aar', status=EXISTS)")
            }
        }
    }

    private fun createAarWithCodeAndJavaResources(): SimpleZip {
        return createAar("valid.aar") {
            withMainJar {
                addClassWithEmptyMethods("com/example/SomeClass", "foo()V")
                addEmptyClasses("com/example/SomeOtherClass")
                addTextFile("/somefile.txt", "foo")
                addBinaryFile("/somefile.data", "foo".toByteArray())
            }
            addSecondaryJar("foo") {
                addClassWithEmptyMethods("com/foo/SomeClass", "foo()V")
                addEmptyClasses("com/foo/SomeOtherClass")
                addTextFile("/foo/file.txt", "foo")
            }
            addSecondaryJar("bar") {
                addClassWithEmptyMethods("com/bar/SomeClass", "foo()V")
                addEmptyClasses("com/bar/SomeOtherClass")
                addBinaryFile("/bar/file.data", "bar".toByteArray())
            }
        }
    }

    @CheckReturnValue
    private fun expectFailure(action: (SimpleSubjectBuilder<AarSubject, Zip>) -> Unit): AssertionError {
        return ExpectFailure.expectFailureAbout(AarSubject.aars(), action)
    }
}
