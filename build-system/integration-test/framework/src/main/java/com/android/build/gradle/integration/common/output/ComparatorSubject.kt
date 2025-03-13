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

import com.google.common.truth.Fact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject

/**
 * Custom truth subject to compare 2 lists of files.
 *
 * This will be renamed in the future.
 */
class ComparatorSubject(
    metadata: FailureMetadata,
    actual: Iterable<String>
): Subject<ComparatorSubject, Iterable<String>>(metadata, actual) {

    companion object {
        /**
         * Method for getting the subject factory (for use with assertAbout())
         */
        internal fun lists(): Factory<ComparatorSubject, Iterable<String>> {
            return Factory<ComparatorSubject, Iterable<String>> { metadata, actual ->
                ComparatorSubject(metadata, actual)
            }
        }
    }

    private data class InnerClassData(
        val enclosingClass: String,
        val innerClassPattern: String,
    )

    fun containsExactly(
        expected: Iterable<String>
    ) {
        // first extract the different expected
        val expectedFolders = expected.filter { it.endsWith('/') }
        val expectedInner = expected
            .filter { it.endsWith('$') }
            .map {
                InnerClassData(it.substring(0, it.length - 1), it)
            }
        // expected is the remaining classes, plus the enclosing classes of the inner classes
        val expectedClasses = (expected - expectedFolders - expectedInner.map { it.innerClassPattern }).toSet()

        // we need to keep track of which expected we've found.
        val foundItems = mutableSetOf<String>()
        val unexpectedValues = mutableListOf<String>()

        for (value in actual()) {
            // start validating this entry against the different types of matches, starting
            // with the most generic, and finishing with the exact match.
            // We have to record if we found any match along the way
            var matchFound = false

            val folder = expectedFolders.findMatch(value)
            if (folder != null) {
                foundItems.add(folder)
                matchFound = true
            }

            val anonymous = expectedInner.findInnerClassMatch(value)
            if (anonymous != null) {
                foundItems.add(anonymous)
                matchFound = true
            }

            if (expectedClasses.contains(value)) {
                foundItems.add(value)
                matchFound = true
            }

            if (!matchFound) {
                // if we are here, that means this value is unexpected.
                unexpectedValues.add(value)
            }
        }

        // computes what was not found.
        val missingItems = expected - foundItems

        if (missingItems.isNotEmpty() || unexpectedValues.isNotEmpty()) {
            val facts = buildList {
                if (missingItems.isNotEmpty()) {
                    if (missingItems.size == 1) {
                        add(Fact.fact("missing", missingItems.first()))
                    } else {
                        add(
                            Fact.fact(
                                "missing (${missingItems.size})",
                                missingItems.sorted().toString()
                            )
                        )
                    }
                }
                if (unexpectedValues.isNotEmpty()) {
                    if (unexpectedValues.size == 1) {
                        add(Fact.fact("unexpected", unexpectedValues.first()))
                    } else {
                        add(
                            Fact.fact(
                                "unexpected (${unexpectedValues.size})",
                                unexpectedValues.sorted().toString()
                            )
                        )
                    }
                }
            }

            if (facts.size == 1) {
                failWithoutActual(facts.single())
            } else {
                failWithoutActual(facts.first(), facts[1])
            }
        }
    }

    private fun List<String>.findMatch(value: String): String? {
        for (potentialMatch in this) {
            if (value.startsWith(potentialMatch)) return potentialMatch
        }

        return null
    }
    private fun List<InnerClassData>.findInnerClassMatch(value: String): String? {
        for (potentialMatch in this) {
            if (value == potentialMatch.enclosingClass || value.startsWith(potentialMatch.innerClassPattern)) {
                return potentialMatch.innerClassPattern
            }
        }

        return null
    }
}
