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

import com.android.build.gradle.integration.common.output.ZipSubject.Companion.zips
import com.google.common.truth.ExpectFailure
import com.google.common.truth.Truth
import com.google.common.truth.TruthFailureSubject
import org.jetbrains.annotations.CheckReturnValue
import org.junit.Test
import kotlin.io.path.deleteExisting

@Suppress("UnstableApiUsage")
class ComparatorSubjectTest: BaseZipSubjectTest() {
    @Test
    fun exactFileMatch() {
        withArchive(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        ).expectSuccessWhenComparingTo(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        )
    }

    @Test
    fun unexpected_single_file() {
        withArchive(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        ).expectFailureWhenComparingTo(
            "com/bar/bar.txt"
        ).andValidateFailure {
            factKeys().containsExactly("value of", "unexpected", "zip was")
            factValue("value of").isEqualTo("zip.entries()")
            factValue("unexpected").isEqualTo("com/foo/foo.txt")
            factValue("zip was").isEqualTo("Zip(name='temp.zip', status=EXISTS)")
        }
    }

    @Test
    fun unexpected_multi_files() {
        withArchive(
            "com/foo/foo.txt",
            "com/foo/bar.txt",
            "com/bar/bar.txt"
        ).expectFailureWhenComparingTo(
            "com/bar/bar.txt"
        ).andValidateFailure {
            factKeys().contains("unexpected (2)")
            factValue("unexpected (2)").isEqualTo("[com/foo/bar.txt, com/foo/foo.txt]")
        }
    }

    @Test
    fun missing_single_file() {
        withArchive(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingTo(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        ).andValidateFailure {
            factKeys().containsExactly("value of", "missing", "zip was")
            factValue("value of").isEqualTo("zip.entries()")
            factValue("missing").isEqualTo("com/foo/foo.txt")
            factValue("zip was").isEqualTo("Zip(name='temp.zip', status=EXISTS)")
        }
    }

    @Test
    fun missing_multi_files() {
        withArchive(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingTo(
            "com/foo/foo.txt",
            "com/foo/bar.txt",
            "com/bar/bar.txt"
        ).andValidateFailure {
            factKeys().contains("missing (2)")
            factValue("missing (2)").isEqualTo("[com/foo/bar.txt, com/foo/foo.txt]")
        }
    }

    @Test
    fun folderMatch() {
        withArchive(
            "com/foo/foo.txt",
            "com/foo/bar.txt",
            "com/bar/bar.txt"
        ).expectSuccessWhenComparingTo(
            "com/foo/",
            "com/bar/"
        )
    }

    @Test
    fun missing_single_folder() {
        withArchive(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingTo(
            "com/bar/",
            "com/foo/"
        ).andValidateFailure {
            factKeys().containsExactly("value of", "missing", "zip was")
            factValue("value of").isEqualTo("zip.entries()")
            factValue("missing").isEqualTo("com/foo/")
            factValue("zip was").isEqualTo("Zip(name='temp.zip', status=EXISTS)")
        }
    }

    @Test
    fun missing_multi_folders() {
        withArchive(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingTo(
            "com/bar/",
            "com/foo/",
            "com/foo2/"
        ).andValidateFailure {
            factKeys().contains("missing (2)")
            factValue("missing (2)").isEqualTo("[com/foo/, com/foo2/]")
        }
    }

    @Test
    fun validate_file_and_parent_folder() {
        withArchive(
            "com/foo/foo.txt",
        ).expectSuccessWhenComparingTo(
            "com/foo/foo.txt",
            "com/foo/",
        )
    }

    @Test
    fun missing_file_in_present_folder() {
        withArchive(
            "com/foo/foo.txt",
        ).expectFailureWhenComparingTo(
            "com/foo/bar.txt",
            "com/foo/",
        ).andValidateFailure {
            factKeys().contains("missing")
            factValue("missing").isEqualTo("com/foo/bar.txt")
        }
    }

    // ---------------------------

    private class ArchiveTester(
        private val zip: SimpleZip
    ) {
        fun expectSuccessWhenComparingTo(vararg items: String) {
            zip.use {
                Truth.assertAbout(zips()).that(it).containsExactly(*items)
            }
            zip.archivePath!!.deleteExisting()
        }

        @CheckReturnValue
        fun expectFailureWhenComparingTo(vararg items: String): AssertionError {
            val error = zip.use { zip ->
                ExpectFailure.expectFailureAbout(zips()) { subjectBuilder ->
                    subjectBuilder.that(zip).containsExactly(*items)
                }
            }
            zip.archivePath!!.deleteExisting()

            return error
        }
    }

    @CheckReturnValue
    private fun withArchive(
        vararg items: String,
    ): ArchiveTester {
        return ArchiveTester(createJar("temp.zip") {
            for (item in items) {
                // the content does not matter, we just validate file list
                addTextFile("/$item", "a")
            }
        })
    }

    fun AssertionError.andValidateFailure(action: TruthFailureSubject.() -> Unit) {
        action(ExpectFailure.assertThat(this))
    }
}
