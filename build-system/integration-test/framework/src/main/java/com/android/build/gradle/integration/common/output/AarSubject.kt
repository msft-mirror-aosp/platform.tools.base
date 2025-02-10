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

import com.android.build.gradle.integration.common.truth.NativeLibrarySubject
import com.android.build.gradle.internal.tasks.AarMetadataReader
import com.android.build.gradle.internal.tasks.AarMetadataTask
import com.android.utils.FileUtils
import com.google.common.truth.FailureMetadata
import com.google.common.truth.StringSubject
import com.google.common.truth.Truth.assertAbout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer
import java.util.regex.Pattern
import kotlin.io.path.inputStream

private val PATTERN_LIBS_JAR = Pattern.compile("^libs/.+$")

@SubjectDsl
class AarSubject(
    metadata: FailureMetadata,
    actual: Zip
): AbstractZipSubject<AarSubject, Zip>(metadata, actual) {

    companion object {
        /**
         * Runs the provided action on an [AarSubject] that
         * is created for the zip at the provided path.
         */
        fun assertThat(path: Path, action: AarSubject.() -> Unit) {
            SimpleZip(path).use {
                action(assertAbout(aars()).that(it))
            }
        }

        /**
         * Runs the provided action on an [AarSubject] that
         * is created for the zip at the provided path.
         */
        fun assertThat(file: File, action: AarSubject.() -> Unit) {
            assertThat(file.toPath(), action)
        }

        /**
         * Runs the provided action on an [AarSubject] that
         * is created for the zip at the provided path.
         */
        @JvmStatic
        fun assertThat(path: Path, action: Consumer<AarSubject>) {
            SimpleZip(path).use {
                action.accept(assertAbout(aars()).that(it))
            }
        }

        /**
         * Runs the provided action on an [AarSubject] that
         * is created for the zip at the provided path.
         */
        @JvmStatic
        fun assertThat(file: File, action: Consumer<AarSubject>) {
            assertThat(file.toPath(), action)
        }

        internal fun assertThat(zip: Zip, action: AarSubject.() -> Unit) {
            action(assertAbout(aars()).that(zip))
        }

        internal fun aars(): Factory<AarSubject, Zip> {
            return Factory<AarSubject, Zip> { metadata, actual ->
                AarSubject(metadata, actual)
            }
        }
    }

    /**
     * Returns a [JarSubject] wrapping the content of the main jar and all the secondary jars.
     */
    fun allJars(): JarSubject {
        exists()

        // Inner zips are automatically closed when the enclosing zip is closed.
        val mainJar = actual().innerZip("classes.jar")
        val secondaryJars = actual().getEntries(PATTERN_LIBS_JAR).mapNotNull { actual().innerZip(it) }

        val allJars = if (mainJar != null) {
            buildList {
                // we want to keep this one first
                add(mainJar)
                addAll(secondaryJars)
            }
        } else secondaryJars

        return check("allJars()").about(JarSubject.jars()).that(MultiZipView(allJars, "allJars"))
    }

    /**
     * Creates a [JarSubject] wrapping the content of the main jar and all the secondary jars,
     * and configure it with the given action
     */
    fun allJars(action: JarSubject.() -> Unit) {
        action(allJars())
    }

    /**
     * Creates a [JarSubject] wrapping the content of the main jar and all the secondary jars,
     * and configure it with the given action
     */
    fun allJars(action: Consumer<JarSubject>) {
        allJars {
            action.accept(this)
        }
    }

    /**
     * returns a [JarSubject] for the main jar of the AAR (classes.jar)
     */
    fun mainJar(): JarSubject = jar("classes.jar", methodName = "mainJar()")

    /**
     * creates a [JarSubject] for the main jar of the AAR (classes.jar), and configures it
     * via the given action.
     */
    fun mainJar(action: JarSubject.() -> Unit) {
        action(mainJar())
    }

    /**
     * creates a [JarSubject] for the main jar of the AAR (classes.jar), and configures it
     * via the given action.
     */
    fun mainJar(action: Consumer<JarSubject>) {
        mainJar {
            action.accept(this)
        }
    }

    /**
     * returns a [JarSubject] for the api jar of the AAR (api.jar).
     */
    fun apiJar(): JarSubject = jar("api.jar", methodName = "apiJar()")

    /**
     * creates a [JarSubject] for the api jar of the AAR (api.jar), and configures it
     * via the given action.
     */
    fun apiJar(action: JarSubject.() -> Unit) {
        action(apiJar())
    }

    /**
     * creates a [JarSubject] for the api jar of the AAR (api.jar), and configures it
     * via the given action.
     */
    fun apiJar(action: Consumer<JarSubject>) {
        apiJar {
            action.accept(this)
        }
    }

    /**
     * Returns all the classes from any secondary jars as a single [JarSubject].
     *
     */
    fun allSecondaryJars(): JarSubject {
        exists()

        // Inner zips are automatically closed when the enclosing zip is closed.
        val secondaryJars = actual().getEntries(PATTERN_LIBS_JAR).mapNotNull { actual().innerZip(it) }

        return check("allSecondaryJars()").about(JarSubject.jars())
            .that(MultiZipView(secondaryJars, "allSecondaryClasses"))
    }

    /**
     * Creates a [JarSubject] representing all the classes from the secondary jars, and configure it
     * with the given action
     */
    fun allSecondaryJars(action: JarSubject.() -> Unit) {
        action(allSecondaryJars())
    }

    /**
     * Creates a [JarSubject] representing all the classes from the secondary jars, and configure it
     * with the given action
     */
    fun allSecondaryJars(action: Consumer<JarSubject>) {
        allSecondaryJars {
            action.accept(this)
        }
    }

    /**
     * returns a [StringSubject] for the Android Manifest of the AAR.
     */
    fun manifest(): StringSubject {
        contains("AndroidManifest.xml")
        return check("manifest()").that(actual().textFile("AndroidManifest.xml"))
    }

    /**
     * returns [ZipSubject] for the Android resources
     *
     * The names of the files do NOT include the res folder.
     */
    fun androidResources(): ZipSubject {
        exists()
        return check("androidResources()").about(ZipSubject.zips()).that(ZipFolderView(actual(), "res"))
    }

    /**
     * Creates a [ZipSubject] representing all the android resources, and configure it
     * with the given action
     */
    fun androidResources(action: ZipSubject.() -> Unit) {
        action(androidResources())
    }

    /**
     * Creates a [ZipSubject] representing all the android resources, and configure it
     * with the given action
     */
    fun androidResources(action: Consumer<ZipSubject>) {
        androidResources {
            action.accept(this)
        }
    }

    /**
     * returns [ZipSubject] for the Android assets
     *
     * The names of the files do NOT include the assets folder.
     */
    fun assets(): ZipSubject {
        exists()
        return check("assets()").about(ZipSubject.zips()).that(ZipFolderView(actual(), "assets"))
    }

    /**
     * Creates a [ZipSubject] representing all the android assets, and configure it
     * with the given action
     */
    fun assets(action: ZipSubject.() -> Unit) {
        action(assets())
    }

    /**
     * Creates a [ZipSubject] representing all the android assets, and configure it
     * with the given action
     */
    fun assets(action: Consumer<ZipSubject>) {
        assets {
            action.accept(this)
        }
    }

    /**
     * returns a [StringSubject] for the text symbold file (R.txt)
     */
    fun textSymbolFile(): StringSubject {
        contains("R.txt")
        return check("textSymbolFile()").that(actual().textFile("R.txt"))
    }

    /**
     * returns a [JarSubject] for the lint jar of the AAR (lint.jar).
     */
    fun lintJar(): JarSubject = jar("lint.jar", methodName = "lintJar()")

    /**
     * creates a [JarSubject] for the lint jar of the AAR (lint.jar), and configures it
     * via the given action.
     */
    fun lintJar(action: JarSubject.() -> Unit) {
        action(lintJar())
    }

    /**
     * creates a [JarSubject] for the lint jar of the AAR (lint.jar), and configures it
     * via the given action.
     */
    fun lintJar(action: Consumer<JarSubject>) {
        lintJar {
            action.accept(this)
        }
    }

    /**
     * returns a [AarMetadataSubject] for the metadata of this AAR
     */
    fun aarMetadata(): AarMetadataSubject {
        contains(AarMetadataTask.AAR_METADATA_ENTRY_PATH)

        val path= actual().getEntry(AarMetadataTask.AAR_METADATA_ENTRY_PATH)

        // this can be null when we're testing the fixture. In normal operation, the call
        // to contains above guarantees that it's not null
        val aarMetadata = path?.let {
            AarMetadataReader.load(it.inputStream())
        } ?: AarMetadataReader.load("".byteInputStream())

        return check("aarMetadata()").about(AarMetadataSubject.aarmetadatas()).that(aarMetadata)
    }

    /**
     * Creates a [AarMetadataSubject] for the metadata of this AAR, and runs the given action on it.
     */
    fun aarMetadata(action: AarMetadataSubject.() -> Unit) {
        action(aarMetadata())
    }

    fun nativeLibrary(path: String): NativeLibrarySubject {
        contains(path)
        val location = actual().getEntry(path)

        // we need to create a temporary file because the subject needs to run command lines against it.
        // TODO inject a TemporaryFolder rule?

        // location can be null when testing the fixture
        val nativeFile = location?.let {
            Files.createTempFile("nativeLibrary_", "_${location.fileName}").also {
                FileUtils.copyFile(location, it)
            }.toFile()
        } ?: Files.createTempFile("empty", ".so").toFile()

        nativeFile.deleteOnExit()

        return check("nativeLibrary($path)").about(NativeLibrarySubject.nativeLibraries()).that(nativeFile)
    }

    private fun jar(path: String, methodName: String = "jar($path)"): JarSubject {
        contains(path)

        // this can be null when we're testing the fixture. In normal operation, the call
        // to contains above guarantees that it's not null
        // It's ok to not close this empty zip as it's not using a real file.
        // Inner zips are automatically closed when the enclosing zip is closed.
        val jar = actual().innerZip(path) ?: SimpleZip(null)

        return check(methodName).about(JarSubject.jars()).that(jar)
    }
}
