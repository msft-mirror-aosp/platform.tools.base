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

import com.android.build.gradle.integration.common.utils.SdkHelper
import com.android.builder.core.ApkInfoParser
import com.android.builder.internal.packaging.IncrementalPackager.APP_METADATA_ENTRY_PATH
import com.android.ide.common.process.DefaultProcessExecutor
import com.android.ide.common.process.ProcessException
import com.android.ide.common.process.ProcessExecutor
import com.android.sdklib.BuildToolInfo
import com.android.testutils.TestUtils
import com.android.testutils.apk.Dex
import com.android.utils.StdLogger
import com.google.common.truth.Fact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.IntegerSubject
import com.google.common.truth.StringSubject
import com.google.common.truth.Truth.assertAbout
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Path
import java.util.Properties
import java.util.function.Consumer
import java.util.regex.Pattern
import kotlin.io.path.bufferedReader
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes

/**
 * A truth subject to validate the content of an APK.
 */
@SubjectDsl
class ApkSubject(
    metadata: FailureMetadata,
    actual: Zip
) : AbstractAndroidArchiveSubject<ApkSubject, Zip>(metadata, actual) {

    companion object {
        /**
         * Runs the provided action on an [ApkSubject] that
         * is created for the zip at the provided path.
         */
        fun assertThat(path: Path, action: ApkSubject.() -> Unit) {
            SimpleZip(path).use {
                action(assertAbout(apks()).that(it))
            }
        }

        /**
         * Runs the provided action on an [ApkSubject] that
         * is created for the zip at the provided path.
         */
        fun assertThat(file: File, action: ApkSubject.() -> Unit) {
            assertThat(file.toPath(), action)
        }

        /**
         * Runs the provided action on an [ApkSubject] that
         * is created for the zip at the provided path.
         */
        @JvmStatic
        fun assertThat(path: Path, action: Consumer<ApkSubject>) {
            SimpleZip(path).use {
                action.accept(assertAbout(apks()).that(it))
            }
        }

        /**
         * Runs the provided action on an [ApkSubject] that
         * is created for the zip at the provided path.
         */
        @JvmStatic
        fun assertThat(file: File, action: Consumer<ApkSubject>) {
            assertThat(file.toPath(), action)
        }

        internal fun assertThat(zip: Zip, action: ApkSubject.() -> Unit) {
            action(assertAbout(apks()).that(zip))
        }

        internal fun apks(): Factory<ApkSubject, Zip> {
            return Factory<ApkSubject, Zip> { metadata, actual ->
                ApkSubject(metadata, actual)
            }
        }
    }

    fun applicationId(): StringSubject {
        return check("applicationId()").that(apkInfo.packageName)
    }

    fun versionCode(): IntegerSubject {
        return check("versionCode()").that(apkInfo.versionCode)
    }

    fun versionName(): StringSubject {
        return check("versionName()").that(apkInfo.versionName)
    }

    /**
     * returns a [StringSubject] for the Android Manifest of the AAR.
     */
    override fun manifest(): StringSubject {
        contains("AndroidManifest.xml")

        val manifestContent = (actual() as? SimpleZip)?.archivePath?.let { zip ->
            val processExecutor: ProcessExecutor =
                DefaultProcessExecutor(StdLogger(StdLogger.Level.ERROR))
            val parser = ApkInfoParser(TestUtils.getAapt2().toFile(), processExecutor)
            val manifestLines = parser.getManifestContent(zip.toFile())

            // we have to sanitize the content because attributes are displayed as:
            //    A: <namespace>:<name>(<hex id of the attribute>)=<value>
            // The id of the attribute makes it harder to text their values
            manifestLines.joinToString(separator = "\n") {
                val matcher = MANIFEST_ATTRIBUTE_PATTERN.matcher(it)
                if (matcher.matches()) {
                    "${matcher.group(1)}${matcher.group(2)}"
                } else {
                    it
                }
            }
        } ?: "actual() not a SimpleZip. Cannot run manifest() on zip views"

        return check("manifest()").that(manifestContent)
    }

    private val MANIFEST_ATTRIBUTE_PATTERN = Pattern.compile("^(.+)\\(0x[0-9a-f]{8}\\)(.+)$")

    /**
     * Returns a [ClassesSubject] for all the code in the APK
     *
     * this includes code from all the dex files.
     */
    override fun classes(): ClassesSubject {
        exists()
        return codeJarSubject
    }

    /**
     * Cached instance of [ClassesSubject] around all the classes dex in the APK
     *
     * Computing entry list over several dex is costly so it's better cached.
     */
    private val codeJarSubject: ClassesSubject by lazy {
        check("classes()")
            .about(DexClassesFromApkSubject.apk())
            .that(actual())
    }

    fun mainDex(): ClassesSubject {
        contains("classes.dex")
        val dexPath = actual().getEntry("classes.dex")!!
        val dex = Dex(dexPath)

        return check("mainDex()").about(DexSubject.dexFiles()).that(listOf(dex))
    }

    fun secondaryDexes(): ClassesSubject {
        exists()
        return check("mainDex()").about(DexSubject.dexFiles()).that(secondaryDexesList)
    }

    override fun jniLibs(): JniSubject {
        exists()
        return check("jniLibs()").about(JniSubjectImpl.libs()).that(ZipFolderView(actual(), "lib"))
    }

    override fun javaResources(): ResourcesSubject {
        exists()
        return javaResourcesSubject
    }

    fun apkMetadata(): PropertiesSubject {
        contains(APP_METADATA_ENTRY_PATH)
        val path= actual().getEntry(APP_METADATA_ENTRY_PATH)

        val properties = Properties()
        path?.let {
            it.bufferedReader(Charsets.UTF_8, it.fileSize().toInt()).use { reader ->
                properties.load(reader)
            }
        }

        return check("apkMetadata()").about(PropertiesSubject.properties()).that(properties)

    }

    fun hasApkSigningBlock() {
        exists()

        // IMPLEMENTATION NOTE: To avoid having to implement too much parsing, this method does not
        // parse the APK to locate the APK Signing Block. Instead, it simply scans the file for the
        // APK Signing Block magic bitstring. If the string is there in the file, it's assumed to
        // contain an APK Signing Block.

        val zipFile: SimpleZip? = actual() as? SimpleZip

        if (zipFile == null) {
            failWithActual(
                Fact.simpleFact("expected to be a direct Zip"),
                Fact.simpleFact("but was ${actual().javaClass.name}")
            )
            return // not needed since fail will throw but needed to handle smart casting of zipFile
        }

        // archivePath is not null since we called exist earlier
        val contents = zipFile.archivePath!!.readBytes()

        outer@ for (contentsOffset in contents.size - APK_SIG_BLOCK_MAGIC.size downTo 0) {
            for (magicOffset in APK_SIG_BLOCK_MAGIC.indices) {
                if (contents[contentsOffset + magicOffset] != APK_SIG_BLOCK_MAGIC[magicOffset]) {
                    continue@outer
                }
            }
            // Found at offset contentsOffset
            return
        }

        // Not found
        failWithActual(Fact.simpleFact("expected to have signing block"))
    }

    /**
     * Validates the APK is properly zipAligned
     */
    fun validateZipAlignment() {
        checkAlignment("validateZipAlignment()")
    }

    /**
     * Validates the APK for page sizes for native libraries.
     */
    fun validatePageAlignment(pageSize: Int) {
        checkAlignment("validateZipAlignment()", pageSize.toString())
    }

    private fun checkAlignment(
        checkName: String,
        pageSize: String? = null) {
        val arguments = buildList {
            add(SdkHelper.getBuildTool(BuildToolInfo.PathId.ZIP_ALIGN).absolutePath)
            add("-c") // check
            add("-v") // verbose
            pageSize?.let {
                add("-P")
                add(it)
            }
            add("4") // default alignment - you always have to use it with zipalign
            val zip = actual() as SimpleZip
            add(zip.archivePath.toString())
        }

        val processBuilder = ProcessBuilder(arguments)
        val result = processBuilder.start().waitFor()
        check(checkName).that(result == 0).isTrue()
    }

    /**
     * Returns a [ResourcesSubject] for all java resources in the APK.
     *
     * Computing entry list by filtering the content of the whole APK can be costly so it's
     * better cached.
     */
    private val javaResourcesSubject by lazy {
        check("javaResources()")
            .about(ApkWithJavaResourcesSubject.javaResources())
            .that(actual())
    }

    private val apkInfo: ApkInfoParser.ApkInfo by lazy(LazyThreadSafetyMode.NONE) {
        val processExecutor: ProcessExecutor =
            DefaultProcessExecutor(StdLogger(StdLogger.Level.ERROR))
        val parser = ApkInfoParser(TestUtils.getAapt2().toFile(), processExecutor)
        try {
            val zipArchive = actual() as SimpleZip
            zipArchive.archivePath ?: throw RuntimeException("Cannot get archivePath from $zipArchive")
            parser.parseApk(zipArchive.archivePath.toFile())
        } catch (e: ProcessException) {
            throw UncheckedIOException(IOException(e))
        }
    }

    /**
     * Precompute all data on classes via dex and cache it as can by costly.
     */
    private val secondaryDexesList: List<Dex> by lazy(LazyThreadSafetyMode.NONE) {
        buildList {
            var index = 2
            // we cannot just take al these entries. we have to do the same that the runtime does, which
            // is search from 2, increasing the index each time. If there is a gap in the indices, then
            // the rest is ignored.
            do {
                val path = actual().getEntry("classes$index.dex") ?: return@buildList
                add(Dex(path))
                index++
            } while (true)
        }
    }

}

private val APK_SIG_BLOCK_MAGIC = byteArrayOf(
    0x41, 0x50, 0x4b, 0x20, 0x53, 0x69, 0x67, 0x20, 0x42, 0x6c, 0x6f, 0x63, 0x6b, 0x20, 0x34,
    0x32
)
