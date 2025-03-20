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
import com.google.common.truth.Truth
import com.google.common.truth.TruthFailureSubject
import org.jetbrains.annotations.CheckReturnValue
import org.junit.Test

@Suppress("UnstableApiUsage")
class ArchiveEntriesSubjectTest {
    @Test
    fun exactFileMatch() {
        withActual(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        ).expectExactMatchWith(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        )
    }

    @Test
    fun exactFolderMatch() {
        withActual(
            "com/foo/foo.txt",
            "com/foo/bar.txt",
            "com/bar/bar.txt"
        ).expectExactMatchWith(
            "com/foo/",
            "com/bar/"
        )
    }

    @Test
    fun fileAndParentFolder() {
        withActual(
            "com/foo/foo.txt",
        ).expectExactMatchWith(
            "com/foo/foo.txt",
            "com/foo/",
        )
    }

    @Test
    fun exactClassMatchWithInnerClass() {
        withActual(
            "com/foo/Foo",
        ).expectExactClassMatchWith(
            "com/foo/Foo$"
        )
    }

    @Test
    fun classAndInnerClass() {
        withActual(
            "com/foo/Foo",
            "com/foo/Foo\$bar",
        ).expectExactClassMatchWith(
            "com/foo/Foo$"
        )
    }

    @Test
    fun exactClassMatchWithoutInnerClass() {
        withActual(
            "com/foo/Foo",
        ).expectExactClassMatchWith(
            "com/foo/Foo$"
        )
    }

    @Test
    fun wrongSingleFile() {
        withActual(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingExactlyTo(
            "com/foo/foo.txt",
        ).andValidateFailure {
            factKeys().containsExactly("expected", "but was").inOrder()
            factValue("expected").isEqualTo("com/foo/foo.txt")
        }
    }

    @Test
    fun wrongSingleFolder() {
        withActual(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingExactlyTo(
            "com/foo/",
        ).andValidateFailure {
            factKeys().containsExactly("expected", "but was").inOrder()
            factValue("expected").isEqualTo("com/foo/")
        }
    }

    @Test
    fun wrongSingleClass() {
        withActual(
            "com/bar/Bar"
        ).expectFailureWhenComparingExactlyTo(
            "com/foo/Foo$",
        ).andValidateFailure {
            factKeys().containsExactly("expected", "but was").inOrder()
            factValue("expected").isEqualTo("com/foo/Foo$")
        }
    }


    @Test
    fun unexpected_single_file() {
        withActual(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        ).expectFailureWhenComparingExactlyTo(
            "com/bar/bar.txt"
        ).andValidateFailure {
            factKeys().containsExactly("unexpected", "---", "expected", "but was").inOrder()
            factValue("unexpected").isEqualTo("com/foo/foo.txt")
            factValue("expected").isEqualTo("com/bar/bar.txt")
        }
    }

    @Test
    fun unexpected_multi_files() {
        withActual(
            "com/foo/foo.txt",
            "com/foo/bar.txt",
            "com/bar/bar.txt"
        ).expectFailureWhenComparingExactlyTo(
            "com/bar/bar.txt",
        ).andValidateFailure {
            factKeys().containsExactly("unexpected (2)", "---", "expected", "but was").inOrder()
            factValue("unexpected (2)").isEqualTo("[com/foo/bar.txt, com/foo/foo.txt]")
            factValue("expected").isEqualTo("com/bar/bar.txt")
        }
    }

    @Test
    fun multiple_unexpected_and_missing() {
        withActual(
            "com/foo/foo.txt",
            "com/foo/foo2.txt",
        ).expectFailureWhenComparingExactlyTo(
            "com/bar/bar.txt",
            "com/bar/bar2.txt"
        ).andValidateFailure {
            factKeys().containsExactly("missing (2)", "unexpected (2)", "---", "expected", "but was").inOrder()
            factValue("missing (2)").isEqualTo("[com/bar/bar.txt, com/bar/bar2.txt]")
            factValue("unexpected (2)").isEqualTo("[com/foo/foo.txt, com/foo/foo2.txt]")
            factValue("expected").isEqualTo("[com/bar/bar.txt, com/bar/bar2.txt]")
        }
    }

    @Test
    fun missing_single_file() {
        withActual(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingExactlyTo(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        ).andValidateFailure {
            factKeys().containsExactly("missing", "---", "expected", "but was").inOrder()
            factValue("missing").isEqualTo("com/foo/foo.txt")
            factValue("expected").isEqualTo("[com/bar/bar.txt, com/foo/foo.txt]")
        }
    }

    @Test
    fun missing_multi_files() {
        withActual(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingExactlyTo(
            "com/foo/foo.txt",
            "com/foo/bar.txt",
            "com/bar/bar.txt"
        ).andValidateFailure {
            factKeys().containsExactly("missing (2)", "---", "expected", "but was").inOrder()
            factValue("missing (2)").isEqualTo("[com/foo/bar.txt, com/foo/foo.txt]")
            factValue("expected").isEqualTo("[com/bar/bar.txt, com/foo/bar.txt, com/foo/foo.txt]")
        }
    }

    @Test
    fun missing_single_folder() {
        withActual(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingExactlyTo(
            "com/bar/",
            "com/foo/"
        ).andValidateFailure {
            factKeys().containsExactly("missing", "---", "expected", "but was").inOrder()
            factValue("missing").isEqualTo("com/foo/")
            factValue("expected").isEqualTo("[com/bar/, com/foo/]")
        }
    }

    @Test
    fun missing_multi_folders() {
        withActual(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingExactlyTo(
            "com/bar/",
            "com/foo/",
            "com/foo2/"
        ).andValidateFailure {
            factKeys().containsExactly("missing (2)", "---", "expected", "but was").inOrder()
            factValue("missing (2)").isEqualTo("[com/foo/, com/foo2/]")
            factValue("expected").isEqualTo("[com/bar/, com/foo/, com/foo2/]")
        }
    }

    @Test
    fun missing_file_in_present_folder() {
        withActual(
            "com/foo/foo.txt",
        ).expectFailureWhenComparingExactlyTo(
            "com/foo/bar.txt",
            "com/foo/",
        ).andValidateFailure {
            factKeys().containsExactly("missing", "---", "expected", "but was").inOrder()
            factValue("missing").isEqualTo("com/foo/bar.txt")
            factValue("expected").isEqualTo("[com/foo/, com/foo/bar.txt]")
        }
    }

    @Test
    fun partialFileMatch() {
        withActual(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        ).expectPartialMatchWith(
            "com/foo/foo.txt",
        )
    }

    @Test
    fun partialExactFileMatch() {
        withActual(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        ).expectPartialMatchWith(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        )
    }

    @Test
    fun partialFolderMatch() {
        withActual(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        ).expectPartialMatchWith(
            "com/foo/",
        )
    }

    @Test
    fun partialClassMatch() {
        withActual(
            "com/foo/Foo",
            "com/bar/Bar",
        ).expectPartialClassMatchWith(
            "com/foo/Foo$"
        )
    }

    @Test
    fun partial_multiple_missings() {
        withActual(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingPartiallyTo(
            "com/foo/foo.txt",
            "com/foo/bar.txt",
            "com/bar/bar.txt"
        ).andValidateFailure {
            factKeys().containsExactly("missing (2)", "---", "expected at least", "but was").inOrder()
            factValue("missing (2)").isEqualTo("[com/foo/bar.txt, com/foo/foo.txt]")
            factValue("expected at least").isEqualTo("[com/bar/bar.txt, com/foo/bar.txt, com/foo/foo.txt]")
        }
    }

    @Test
    fun partial_single_missing() {
        withActual(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingPartiallyTo(
            "com/foo/foo.txt",
            "com/bar/bar.txt"
        ).andValidateFailure {
            factKeys().containsExactly("missing", "---", "expected at least", "but was").inOrder()
            factValue("missing").isEqualTo("com/foo/foo.txt")
            factValue("expected at least").isEqualTo("[com/bar/bar.txt, com/foo/foo.txt]")
        }
    }

    @Test
    fun partial_missing_single_expected() {
        withActual(
            "com/bar/foo.txt",
            "com/bar/bar.txt"
        ).expectFailureWhenComparingPartiallyTo(
            "com/foo/foo.txt",
        ).andValidateFailure {
            factKeys().containsExactly("missing", "---", "expected to contain", "but was").inOrder()
            factValue("missing").isEqualTo("com/foo/foo.txt")
            factValue("expected to contain").isEqualTo("com/foo/foo.txt")
        }
    }

    @Test
    fun partial_wrong_single_item() {
        withActual(
            "com/bar/bar.txt"
        ).expectFailureWhenComparingPartiallyTo(
            "com/foo/foo.txt",
        ).andValidateFailure {
            factKeys().containsExactly("expected to contain", "but was").inOrder()
            factValue("expected to contain").isEqualTo("com/foo/foo.txt")
        }
    }


    // ---------------------------

    private class StringListTester(
        private val actual: Collection<String>
    ) {
        fun expectExactMatchWith(vararg items: String) {
            Truth.assertAbout(ArchiveEntriesSubject.files())
                .that(actual)
                .containsExactly(items.toList())
        }

        fun expectPartialMatchWith(vararg items: String) {
            Truth.assertAbout(ArchiveEntriesSubject.files())
                .that(actual)
                .containsAtLeast(items.toList())
        }

        fun expectExactClassMatchWith(vararg items: String) {
            Truth.assertAbout(ArchiveEntriesSubject.classes())
                .that(actual)
                .containsExactly(items.toList())
        }

        fun expectPartialClassMatchWith(vararg items: String) {
            Truth.assertAbout(ArchiveEntriesSubject.classes())
                .that(actual)
                .containsAtLeast(items.toList())
        }

        @CheckReturnValue
        fun expectFailureWhenComparingExactlyTo(vararg items: String): TestError {
            return expectFailure(
                mode = ArchiveEntriesSubject.Mode.FILES,
                items = items
            ) {
                containsExactly(it)
            }
        }

        @CheckReturnValue
        fun expectFailureWhenComparingPartiallyTo(vararg items: String): TestError {
            return expectFailure(
                mode = ArchiveEntriesSubject.Mode.FILES,
                items = items
            ) {
                containsAtLeast(it)
            }
        }

        @CheckReturnValue
        private fun expectFailure(
            mode: ArchiveEntriesSubject.Mode = ArchiveEntriesSubject.Mode.FILES,
            vararg items: String,
            action: ArchiveEntriesSubject.(Collection<String>) -> Unit
        ): TestError {
            val factory = if (mode == ArchiveEntriesSubject.Mode.FILES)
                ArchiveEntriesSubject.files()
            else
                ArchiveEntriesSubject.classes()

            return TestError(
                error = ExpectFailure.expectFailureAbout(factory) { subjectBuilder ->
                    action(subjectBuilder.that(actual), items.toList())
                },
                actual = actual
            )
        }
    }

    @CheckReturnValue
    private fun withActual(vararg items: String): StringListTester {
        return StringListTester(items.toList())
    }

    class TestError(val error: AssertionError, val actual: Collection<String>)

    fun TestError.andValidateFailure(action: TruthFailureSubject.() -> Unit) {
        val failureSubject = ExpectFailure.assertThat(this.error)
        action(failureSubject)

        // automatically validate "but was"
        failureSubject.factValue("but was").isEqualTo(actual.sorted().toString())
    }
}
