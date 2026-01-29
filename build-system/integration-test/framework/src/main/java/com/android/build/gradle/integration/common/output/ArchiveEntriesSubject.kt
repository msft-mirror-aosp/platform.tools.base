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

/** Custom truth subject to compare 2 lists of files that are coming from an archive. */
class ArchiveEntriesSubject internal constructor(private val mode: Mode, metadata: FailureMetadata, actual: Collection<String>) :
  Subject<ArchiveEntriesSubject, Collection<String>>(metadata, actual) {

  internal enum class Mode {
    FILES,
    CLASSES,
  }

  companion object {
    internal fun files(): Factory<ArchiveEntriesSubject, Collection<String>> {
      return Factory<ArchiveEntriesSubject, Collection<String>> { metadata, actual -> ArchiveEntriesSubject(Mode.FILES, metadata, actual) }
    }

    internal fun classes(): Factory<ArchiveEntriesSubject, Collection<String>> {
      return Factory<ArchiveEntriesSubject, Collection<String>> { metadata, actual ->
        ArchiveEntriesSubject(Mode.CLASSES, metadata, actual)
      }
    }
  }

  /** Internal storage for decomposing inner-class type expected entries */
  private data class InnerClassData(val enclosingClass: String, val innerClassPattern: String)

  fun containsExactly(expected: Collection<String>) {
    checkContains(expected, exactMatch = true)
  }

  fun containsAtLeast(expected: Collection<String>) {
    checkContains(expected, exactMatch = false)
  }

  private fun checkContains(expected: Collection<String>, exactMatch: Boolean) {
    // first extract the different expected
    val expectedFolders = expected.filter { it.endsWith('/') }

    // this is for inner classes. When the mode is not CLASSES, that list should be empty.
    val expectedInner =
      if (mode == Mode.CLASSES) {
        expected.filter { it.endsWith('$') }.map { InnerClassData(it.substring(0, it.length - 1), it) }
      } else {
        listOf()
      }

    // These are items that must match exactly.
    val expectedItems = (expected - expectedFolders - expectedInner.map { it.innerClassPattern }).toSet()

    // we need to keep track of which expected we've found.
    val foundItems = mutableSetOf<String>()
    val unexpectedValues = mutableListOf<String>()

    for (value in actual()) {
      // start validating this entry against the different types of matches, starting
      // with the most generic, and finishing with the exact match.
      // We have to record if we found any match along the way
      var matchFound = false

      val folder = expectedFolders.findFolderMatch(value)
      if (folder != null) {
        foundItems.add(folder)
        matchFound = true
      }

      val anonymous = expectedInner.findInnerClassMatch(value)
      if (anonymous != null) {
        foundItems.add(anonymous)
        matchFound = true
      }

      if (expectedItems.contains(value)) {
        foundItems.add(value)
        matchFound = true
      }

      // If we are here, that means this value is unexpected.
      // Only care about it if we need an exact match
      if (exactMatch && !matchFound) {
        unexpectedValues.add(value)
      }
    }

    // computes what was not found.
    val missingItems = expected - foundItems
    if (missingItems.isEmpty() && unexpectedValues.isEmpty()) return

    val facts = buildList {
      // if both actual and expected are single items, skip the whole
      // missing/unexpected facts as it's redundant with expected/but was
      if (actual().size > 1 || expected.size > 1) {
        if (missingItems.isNotEmpty()) {
          add(missingItems.toFact(singleLabel = "missing", multipleLabelAction = { "missing ($size)" }))
        }

        if (unexpectedValues.isNotEmpty()) {
          add(unexpectedValues.toFact(singleLabel = "unexpected", multipleLabelAction = { "unexpected ($size)" }))
        }
      }

      // if we've already added facts, add a separator, like Truth already does
      if (isNotEmpty()) {
        add(Fact.simpleFact("---"))
      }

      if (exactMatch) {
        add(expected.sorted().toFact(singleLabel = "expected", multipleLabel = "expected"))
      } else {
        add(expected.sorted().toFact(singleLabel = "expected to contain", multipleLabel = "expected at least"))
      }
      add(Fact.fact("but was", actual().sorted().toString()))
    }

    val factArray = facts.subList(1, facts.size).toTypedArray()
    failWithoutActual(facts.first(), *factArray)
  }

  private fun List<String>.findFolderMatch(value: String): String? {
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

  private fun List<String>.toFact(singleLabel: String, multipleLabel: String): Fact {
    val list = this.sorted()
    return if (list.size > 1) {
      Fact.fact(multipleLabel, list)
    } else {
      Fact.fact(singleLabel, list.first())
    }
  }

  private fun List<String>.toFact(singleLabel: String, multipleLabelAction: List<String>.() -> String): Fact {
    val list = this.sorted()
    return if (list.size > 1) {
      Fact.fact(multipleLabelAction(list), list)
    } else {
      Fact.fact(singleLabel, list.first())
    }
  }
}
