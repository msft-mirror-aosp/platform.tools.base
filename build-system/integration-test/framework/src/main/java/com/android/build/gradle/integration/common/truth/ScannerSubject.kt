@file:JvmName("ScannerSubjectUtils")

/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.integration.common.truth

import com.google.common.truth.Fact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Truth
import java.util.Scanner

class ScannerSubject(failureMetadata: FailureMetadata, actual: Scanner, private val name: String? = null) :
  Subject<ScannerSubject, Scanner>(failureMetadata, actual) {

  companion object {
    internal fun scanners(name: String? = null): Factory<ScannerSubject, Scanner> {
      return Factory<ScannerSubject, Scanner> { failureMetadata, subject -> ScannerSubject(failureMetadata, subject, name) }
    }

    @JvmStatic
    @JvmOverloads
    fun assertThat(scanner: Scanner, name: String? = null): ScannerSubject {
      return Truth.assertAbout(scanners(name)).that(scanner)
    }
  }

  fun contains(string: String) {
    if (string.contains("\r\n")) {
      fail(Fact.simpleFact("expected string contains windows-style line separator"))
    }

    val requestedLines = string.split("\n")
    if (requestedLines.isEmpty()) {
      fail(Fact.simpleFact("expected string is empty"))
    }

    val expectedIterator = requestedLines.iterator()
    var nextString = expectedIterator.next()
    while (actual().hasNextLine()) {
      val nextLine = actual().nextLine()
      while (nextLine.contains(nextString)) {
        if (expectedIterator.hasNext()) {
          nextString = expectedIterator.next()
        } else {
          return
        }
      }
    }

    if (requestedLines.isEmpty()) {
      fail(Fact.simpleFact("expected to contain '$string'"))
    } else {
      // build a message with the expected content on multi line with an offset
      val message = buildString {
        appendLine("expected to contain:")
        for (line in requestedLines) {
          appendLine("|$line")
        }
      }
      fail(Fact.simpleFact(message))
    }
  }

  fun doesNotContain(string: String) {
    if (string.contains("\r\n")) {
      fail(Fact.simpleFact("expected string contains windows-style line separator"))
    }

    val requestedLines = string.split("\n")
    if (requestedLines.isEmpty()) {
      fail(Fact.simpleFact("expected string is empty"))
    }

    val expectedIterator = requestedLines.iterator()
    var nextExpectedString = expectedIterator.next()
    while (actual().hasNextLine()) {
      if (actual().nextLine().contains(nextExpectedString)) {
        if (expectedIterator.hasNext()) {
          nextExpectedString = expectedIterator.next()
        } else {

          if (requestedLines.isEmpty()) {
            fail(Fact.simpleFact("expected to not contain '$string'"))
          } else {
            // build a message with the expected content on multi line with an offset
            val message = buildString {
              appendLine("expected to not contain:")
              for (line in requestedLines) {
                appendLine("|$line")
              }
            }
            fail(Fact.simpleFact(message))
          }
        }
      }
    }
  }

  private fun fail(fact: Fact) {
    if (name != null) {
      failWithoutActual(Fact.fact("value of", name), fact)
    } else {
      failWithoutActual(fact)
    }
  }
}

/**
 * Invokes passed action on each line and close the Scanner instance.
 *
 * @param action the lambda to run on each line.
 */
fun Scanner.forEachLine(action: (String) -> Unit) {
  this.use {
    while (it.hasNextLine()) {
      action(it.nextLine())
    }
  }
}
